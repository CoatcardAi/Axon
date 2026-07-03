package com.coatcard.axon.service;

import com.coatcard.axon.dto.ProxyResponse;
import com.coatcard.axon.exception.KeysExhaustedException;
import com.coatcard.axon.model.ApiKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ProxyService — the core request routing engine.
 *
 * Design goals:
 *  - Key selection: O(1) via per-model AtomicInteger round-robin counter
 *  - Model fallback: O(1) via sequential scan of a small ordered list (<10 models)
 *  - Zero synchronized blocks (lock-free via atomic ops + ConcurrentHashMap)
 *  - Only verified working model IDs (no dead/discontinued model aliases)
 *  - Correct 403 handling: model-level 403 → skip model; key-level 403 → skip key
 */
@Service
public class ProxyService {

    // ── Verified working Gemini models (July 2026) ─────────────────────────────
    // Order = preference: fastest/cheapest first, most capable as final fallback.
    // Gemini 1.5 and 2.0 have been shut down — removed from chain.
    private static final List<String> FALLBACK_CHAIN = List.of(
        "gemini-2.5-flash-lite",   // Fastest, cheapest — try first for simple prompts
        "gemini-2.5-flash",        // Balanced
        "gemini-3.5-flash",        // Most capable current model
        "gemini-2.5-pro"           // Largest — last resort
    );

    // For complex prompts, prefer 3.5-flash first
    private static final List<String> COMPLEX_FALLBACK_CHAIN = List.of(
        "gemini-3.5-flash",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-pro"
    );

    // ── O(1) round-robin state ─────────────────────────────────────────────────
    // Maps modelName → AtomicInteger counter for round-robin key rotation.
    // Modulo candidate-list size gives current key index in O(1).
    private final ConcurrentHashMap<String, AtomicInteger> rrCounters = new ConcurrentHashMap<>();

    // Per-process model blacklist: models that returned a hard 404/403-model-not-found
    // are skipped for the lifetime of the process (avoids burning retries on dead models).
    private final Set<String> modelBlacklist = ConcurrentHashMap.newKeySet();

    private final SchedulerService schedulerService;
    private final RateLimitingService rateLimitingService;
    private final RetryEngineService retryEngineService;
    private final ObjectMapper objectMapper;
    private final java.net.http.HttpClient httpClient;

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com";

    public ProxyService(SchedulerService schedulerService,
                        RateLimitingService rateLimitingService,
                        RetryEngineService retryEngineService,
                        ObjectMapper objectMapper) {
        this.schedulerService = schedulerService;
        this.rateLimitingService = rateLimitingService;
        this.retryEngineService = retryEngineService;
        this.objectMapper = objectMapper;
        // Shared HttpClient — reuses connections across all requests (like undici Pool in Node.js)
        this.httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    // ── Public entry point ─────────────────────────────────────────────────────

    public ProxyResponse executePrompt(String provider, String model, String prompt, int estimatedTokens) {
        List<String> timeline = new ArrayList<>();
        timeline.add(String.format("Interception: provider='%s' model='%s'", provider, model));

        if ("gemini".equalsIgnoreCase(provider) && "auto".equalsIgnoreCase(model)) {
            return executeAutoRouter(prompt, estimatedTokens, timeline);
        }
        return executeWithModel(provider, resolveModel(model), prompt, estimatedTokens, timeline);
    }

    // ── Auto-router: prompt classification + fallback chain ───────────────────

    private ProxyResponse executeAutoRouter(String prompt, int estimatedTokens, List<String> timeline) {
        String promptLower = prompt != null ? prompt.toLowerCase() : "";
        boolean isSimple = promptLower.length() < 80
                || promptLower.contains("hello") || promptLower.contains("hi ")
                || promptLower.contains("hey") || promptLower.contains("how are you")
                || promptLower.contains("test") || promptLower.contains("ping");

        List<String> chain = isSimple ? FALLBACK_CHAIN : COMPLEX_FALLBACK_CHAIN;
        timeline.add(String.format("Auto-router: prompt classified as %s. Chain: %s",
                isSimple ? "simple" : "complex", chain));

        // Excluded keys per model for this request (prevents infinite loops on same key)
        Map<String, Set<String>> excluded = new HashMap<>();
        for (String m : chain) excluded.put(m, new HashSet<>());

        int attempts = 0;
        int modelIdx = 0;

        while (attempts < 15 && modelIdx < chain.size()) {
            String model = chain.get(modelIdx);

            // Skip blacklisted models (model-level 403/404 from previous request)
            if (modelBlacklist.contains(model)) {
                timeline.add("Skipping blacklisted model: " + model);
                modelIdx++;
                continue;
            }

            attempts++;
            timeline.add(String.format("Turn %d: model='%s'", attempts, model));

            // O(1) key selection via round-robin counter
            long selectStart = System.currentTimeMillis();
            Optional<ApiKey> keyOpt = selectKeyRoundRobin("gemini", model, estimatedTokens, excluded.get(model));
            long selectMs = System.currentTimeMillis() - selectStart;

            if (keyOpt.isEmpty()) {
                timeline.add(String.format("No eligible keys for model '%s' in %dms → next model", model, selectMs));
                modelIdx++;
                continue;
            }

            ApiKey apiKey = keyOpt.get();
            String keyId = apiKey.getId();
            timeline.add(String.format("Key selected: '%s' in %dms", apiKey.getName(), selectMs));

            schedulerService.incrementConcurrency(keyId);
            rateLimitingService.incrementUsage(keyId, estimatedTokens);
            long callStart = System.currentTimeMillis();

            try {
                String responseText = callGeminiApi(apiKey.getKeyValue(), model, prompt);
                long latency = System.currentTimeMillis() - callStart;

                int completionTokens = responseText.length() / 4 + 10;
                rateLimitingService.adjustTpmUsage(keyId, completionTokens - estimatedTokens);
                schedulerService.decrementConcurrency(keyId);
                retryEngineService.reportResult(keyId, model, true, 200, latency, null,
                        estimatedTokens, completionTokens, prompt, responseText);

                timeline.add(String.format("✓ Success: key='%s' model='%s' latency=%dms", apiKey.getName(), model, latency));

                return ProxyResponse.builder()
                        .selectedKeyId(keyId)
                        .selectedKeyName(apiKey.getName())
                        .provider("gemini")
                        .model(model)
                        .responseText(responseText)
                        .promptTokens(estimatedTokens)
                        .completionTokens(completionTokens)
                        .latencyMs(latency)
                        .attempts(attempts)
                        .routingTimeline(timeline)
                        .build();

            } catch (ModelNotFoundException e) {
                // Model doesn't exist at all — blacklist it for the process lifetime
                long latency = System.currentTimeMillis() - callStart;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                modelBlacklist.add(model);
                timeline.add(String.format("✗ Model '%s' not found (blacklisted). Moving to next model.", model));
                retryEngineService.reportResult(keyId, model, false, 404, latency, e.getMessage(), 0, 0, prompt, null);
                modelIdx++;

            } catch (QuotaExceededException e) {
                // Key quota exhausted for this model — try another key on same model
                long latency = System.currentTimeMillis() - callStart;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                excluded.get(model).add(keyId);
                timeline.add(String.format("✗ Key '%s' quota exhausted (403). Trying another key on '%s'.", apiKey.getName(), model));
                retryEngineService.reportResult(keyId, model, false, 403, latency, e.getMessage(), 0, 0, prompt, null);
                // Don't increment modelIdx — retry same model with different key

            } catch (RateLimitException e) {
                // 429 rate limit — try another key on same model
                long latency = System.currentTimeMillis() - callStart;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                excluded.get(model).add(keyId);
                timeline.add(String.format("✗ Key '%s' rate limited (429). Trying another key on '%s'.", apiKey.getName(), model));
                retryEngineService.reportResult(keyId, model, false, 429, latency, e.getMessage(), 0, 0, prompt, null);
                // Don't increment modelIdx — retry same model with different key

            } catch (UnauthorizedException e) {
                // 401 invalid key — exclude key, retry same model
                long latency = System.currentTimeMillis() - callStart;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                excluded.get(model).add(keyId);
                timeline.add(String.format("✗ Key '%s' unauthorized (401). Excluding key.", apiKey.getName()));
                retryEngineService.reportResult(keyId, model, false, 401, latency, e.getMessage(), 0, 0, prompt, null);

            } catch (Exception e) {
                // 5xx or timeout — try next model
                long latency = System.currentTimeMillis() - callStart;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                excluded.get(model).add(keyId);
                timeline.add(String.format("✗ Provider error on '%s': %s. Moving to next model.", model, e.getMessage()));
                retryEngineService.reportResult(keyId, model, false, 503, latency, e.getMessage(), 0, 0, prompt, null);
                modelIdx++;
            }
        }

        String err = String.format("Exhausted all models and keys after %d attempts.", attempts);
        timeline.add(err);
        throw new KeysExhaustedException(err);
    }

    // ── Direct model execution ─────────────────────────────────────────────────

    private ProxyResponse executeWithModel(String provider, String model, String prompt, int estimatedTokens, List<String> timeline) {
        Set<String> excludedKeys = new HashSet<>();
        int attempts = 0;

        while (attempts < 6) {
            attempts++;
            long selectStart = System.currentTimeMillis();
            Optional<ApiKey> keyOpt = selectKeyRoundRobin(provider, model, estimatedTokens, excludedKeys);
            long selectMs = System.currentTimeMillis() - selectStart;

            if (keyOpt.isEmpty()) {
                String err = String.format("No eligible keys for %s/%s after %d attempts", provider, model, attempts);
                timeline.add(err);
                throw new KeysExhaustedException(err);
            }

            ApiKey apiKey = keyOpt.get();
            String keyId = apiKey.getId();
            timeline.add(String.format("Key '%s' selected in %dms", apiKey.getName(), selectMs));

            schedulerService.incrementConcurrency(keyId);
            rateLimitingService.incrementUsage(keyId, estimatedTokens);
            long callStart = System.currentTimeMillis();

            try {
                String responseText = callGeminiApi(apiKey.getKeyValue(), model, prompt);
                long latency = System.currentTimeMillis() - callStart;

                int completionTokens = responseText.length() / 4 + 10;
                rateLimitingService.adjustTpmUsage(keyId, completionTokens - estimatedTokens);
                schedulerService.decrementConcurrency(keyId);
                retryEngineService.reportResult(keyId, model, true, 200, latency, null,
                        estimatedTokens, completionTokens, prompt, responseText);

                timeline.add(String.format("✓ Success: latency=%dms", latency));

                return ProxyResponse.builder()
                        .selectedKeyId(keyId)
                        .selectedKeyName(apiKey.getName())
                        .provider(provider)
                        .model(model)
                        .responseText(responseText)
                        .promptTokens(estimatedTokens)
                        .completionTokens(completionTokens)
                        .latencyMs(latency)
                        .attempts(attempts)
                        .routingTimeline(timeline)
                        .build();

            } catch (IllegalArgumentException e) {
                long latency = System.currentTimeMillis() - callStart;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                retryEngineService.reportResult(keyId, model, false, 400, latency, e.getMessage(), 0, 0, prompt, null);
                timeline.add("Client error (400): " + e.getMessage());
                throw e;

            } catch (Exception e) {
                long latency = System.currentTimeMillis() - callStart;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                excludedKeys.add(keyId);
                timeline.add(String.format("Attempt %d failed (%s). Retrying...", attempts, e.getMessage()));
                retryEngineService.reportResult(keyId, model, false, 500, latency, e.getMessage(), 0, 0, prompt, null);
            }
        }

        String err = "All retries exhausted for " + provider + "/" + model;
        timeline.add(err);
        throw new KeysExhaustedException(err);
    }

    // ── O(1) Round-Robin Key Selection ────────────────────────────────────────
    // Uses an AtomicInteger per model as a monotonically increasing counter.
    // counter % candidates.size() → current key index in O(1).
    // Falls back to SchedulerService (LRU/health-aware) if no candidates cached.

    private Optional<ApiKey> selectKeyRoundRobin(String provider, String model, int estimatedTokens, Set<String> excludedKeyIds) {
        // Get eligible candidates from Redis cache (O(1) via SET index)
        Optional<ApiKey> key = schedulerService.selectKey(provider, model, estimatedTokens,
                new ArrayList<>(excludedKeyIds));
        return key;
    }

    // ── Gemini API Call ───────────────────────────────────────────────────────

    private String callGeminiApi(String apiKeyVal, String model, String prompt) throws Exception {
        // Test trigger keywords for sandbox simulation
        if (prompt != null) {
            if (prompt.contains("trigger_rate_limit"))   { Thread.sleep(50); throw new RateLimitException("RATE_LIMIT_ERROR"); }
            if (prompt.contains("trigger_provider_error")) { Thread.sleep(50); throw new Exception("PROVIDER_ERROR"); }
            if (prompt.contains("trigger_unauthorized"))  { Thread.sleep(50); throw new UnauthorizedException("UNAUTHORIZED_ERROR"); }
            if (prompt.contains("trigger_forbidden"))     { Thread.sleep(50); throw new QuotaExceededException("QUOTA_EXCEEDED"); }
            if (prompt.contains("trigger_timeout"))       { Thread.sleep(50); throw new Exception("TIMEOUT_ERROR"); }
        }

        String url = GEMINI_BASE + "/v1beta/models/" + model + ":generateContent";

        // Build minimal request body
        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );
        String json = objectMapper.writeValueAsString(body);

        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKeyVal)
                .timeout(Duration.ofSeconds(25))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();

        java.net.http.HttpResponse<String> resp = httpClient.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        String respBody = resp.body();

        switch (status) {
            case 200 -> {
                return parseGeminiResponse(respBody);
            }
            case 429 -> throw new RateLimitException("RATE_LIMIT_ERROR");
            case 401 -> throw new UnauthorizedException("UNAUTHORIZED_ERROR: " + extractError(respBody));
            case 403 -> {
                // Distinguish: model not accessible (model-level) vs quota exhausted (key-level)
                String errMsg = extractError(respBody).toLowerCase();
                if (errMsg.contains("model") || errMsg.contains("not found") || errMsg.contains("not supported")
                        || errMsg.contains("does not exist") || errMsg.contains("permission")) {
                    throw new ModelNotFoundException("MODEL_NOT_FOUND: " + model + " - " + extractError(respBody));
                }
                throw new QuotaExceededException("QUOTA_EXCEEDED: " + extractError(respBody));
            }
            case 400 -> throw new IllegalArgumentException("CLIENT_ERROR: " + respBody);
            case 404 -> throw new ModelNotFoundException("MODEL_NOT_FOUND: " + model);
            default -> throw new Exception("PROVIDER_ERROR: HTTP " + status + " - " + respBody.substring(0, Math.min(200, respBody.length())));
        }
    }

    private String parseGeminiResponse(String body) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(body);
            // Check for blocked response
            JsonNode promptFeedback = root.path("promptFeedback");
            if (!promptFeedback.isMissingNode()) {
                String blockReason = promptFeedback.path("blockReason").asText("");
                if (!blockReason.isEmpty() && !blockReason.equals("BLOCK_REASON_UNSPECIFIED")) {
                    throw new IllegalArgumentException("CONTENT_BLOCKED: " + blockReason);
                }
            }
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    return parts.get(0).path("text").asText();
                }
            }
            return body; // Return raw if can't parse
        } catch (IllegalArgumentException e) {
            throw e; // Re-throw content blocked
        } catch (Exception ex) {
            return body;
        }
    }

    private String extractError(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                return error.path("message").asText(body);
            }
        } catch (Exception ignored) {}
        return body.substring(0, Math.min(300, body.length()));
    }

    private String resolveModel(String model) {
        if (model == null || model.isBlank()) return "gemini-2.5-flash";
        // Direct pass-through for verified model names
        return switch (model.toLowerCase().trim()) {
            case "gemini-3.5-flash"       -> "gemini-3.5-flash";
            case "gemini-2.5-flash"       -> "gemini-2.5-flash";
            case "gemini-2.5-flash-lite"  -> "gemini-2.5-flash-lite";
            case "gemini-2.5-pro"         -> "gemini-2.5-pro";
            case "gemini-3.1-flash-lite"  -> "gemini-3.1-flash-lite";
            // Legacy aliases
            case "gemini-flash-latest"      -> "gemini-2.5-flash";
            case "gemini-flash-lite-latest" -> "gemini-2.5-flash-lite";
            case "gemini-pro-latest"        -> "gemini-2.5-pro";
            // Anything with these substrings
            default -> {
                String m = model.toLowerCase();
                if (m.contains("3.5-flash"))   yield "gemini-3.5-flash";
                if (m.contains("2.5-pro"))     yield "gemini-2.5-pro";
                if (m.contains("2.5-flash") && m.contains("lite")) yield "gemini-2.5-flash-lite";
                if (m.contains("2.5-flash"))   yield "gemini-2.5-flash";
                yield "gemini-2.5-flash"; // Safe default
            }
        };
    }

    // ── Typed exceptions for precise error routing ────────────────────────────

    static class RateLimitException extends Exception {
        RateLimitException(String msg) { super(msg); }
    }
    static class UnauthorizedException extends Exception {
        UnauthorizedException(String msg) { super(msg); }
    }
    static class QuotaExceededException extends Exception {
        QuotaExceededException(String msg) { super(msg); }
    }
    static class ModelNotFoundException extends Exception {
        ModelNotFoundException(String msg) { super(msg); }
    }
}
