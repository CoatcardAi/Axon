package com.coatcard.axon.service;

import com.coatcard.axon.dto.ProxyResponse;
import com.coatcard.axon.exception.KeysExhaustedException;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.repository.UsageLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class ProxyService {

    private final SchedulerService schedulerService;
    private final RateLimitingService rateLimitingService;
    private final CooldownService cooldownService;
    private final UsageLogRepository usageLogRepository;
    private final RetryEngineService retryEngineService;
    private final ObjectMapper objectMapper;

    public ProxyService(SchedulerService schedulerService,
                        RateLimitingService rateLimitingService,
                        CooldownService cooldownService,
                        UsageLogRepository usageLogRepository,
                        RetryEngineService retryEngineService,
                        ObjectMapper objectMapper) {
        this.schedulerService = schedulerService;
        this.rateLimitingService = rateLimitingService;
        this.cooldownService = cooldownService;
        this.usageLogRepository = usageLogRepository;
        this.retryEngineService = retryEngineService;
        this.objectMapper = objectMapper;
    }

    public ProxyResponse executePrompt(String provider, String model, String prompt, int estimatedTokens) {
        if ("gemini".equalsIgnoreCase(provider) && "auto".equalsIgnoreCase(model)) {
            return executeAutoGeminiPrompt(prompt, estimatedTokens);
        }

        List<String> timeline = new ArrayList<>();
        timeline.add(String.format("Interception: Received request for provider '%s' and model '%s'.", provider.toUpperCase(), model));
        return executePromptInternal(provider, model, prompt, estimatedTokens, timeline);
    }

    private ProxyResponse executeAutoGeminiPrompt(String prompt, int estimatedTokens) {
        List<String> timeline = new ArrayList<>();
        timeline.add("Chatbot Interception: Model set to 'auto'. Analyzing prompt...");

        // 1. Determine preferred model based on length and key terms
        String preferredModel = "gemini-3.5-flash";
        String promptLower = prompt != null ? prompt.toLowerCase() : "";
        
        boolean isSimple = promptLower.length() < 50 || 
                           promptLower.contains("hello") || 
                           promptLower.contains("hi") || 
                           promptLower.contains("hey") || 
                           promptLower.contains("test") ||
                           promptLower.contains("how are you");

        if (isSimple) {
            preferredModel = "gemini-2.5-flash-lite";
            timeline.add(String.format("Analysis: Prompt identified as simple/conversational. Selecting lightweight model '%s' as preferred candidate.", preferredModel));
        } else {
            timeline.add(String.format("Analysis: Prompt identified as complex/structured. Selecting high-capacity model '%s' as preferred candidate.", preferredModel));
        }

        // 2. Define fallback sequence of Gemini models
        List<String> fallbackChain = List.of(
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-flash-latest",
            "gemini-2.5-flash-lite",
            "gemini-flash-lite-latest",
            "gemma-4-31b-it",
            "gemma-4-26b-a4b-it",
            "gemini-3.1-flash-lite-preview"
        );

        // Reorder list to try preferredModel first
        List<String> tryOrder = new ArrayList<>();
        tryOrder.add(preferredModel);
        for (String m : fallbackChain) {
            if (!m.equalsIgnoreCase(preferredModel)) {
                tryOrder.add(m);
            }
        }

        // 3. Flat routing loop: try up to 20 turns
        int globalAttempts = 0;
        int maxGlobalAttempts = 20;
        int currentModelIdx = 0;
        
        // Track excluded keys per model to avoid selecting them again
        java.util.Map<String, List<String>> excludedKeysMap = new java.util.HashMap<>();
        for (String m : tryOrder) {
            excludedKeysMap.put(m, new ArrayList<>());
        }

        while (globalAttempts < maxGlobalAttempts && currentModelIdx < tryOrder.size()) {
            globalAttempts++;
            String currentModel = tryOrder.get(currentModelIdx);
            timeline.add(String.format("Turn %d: Trying model '%s'. Excluded keys on this model: %s", 
                globalAttempts, currentModel, excludedKeysMap.get(currentModel)));

            // Select key
            Optional<ApiKey> keyOpt;
            synchronized (schedulerService) {
                keyOpt = schedulerService.selectKey("gemini", currentModel, estimatedTokens, excludedKeysMap.get(currentModel));
                if (keyOpt.isPresent()) {
                    schedulerService.incrementConcurrency(keyOpt.get().getId());
                    rateLimitingService.incrementUsage(keyOpt.get().getId(), estimatedTokens);
                    timeline.add(String.format("Selection: Selected key '%s' (%s) for model '%s'.", 
                        keyOpt.get().getName(), keyOpt.get().getId(), currentModel));
                }
            }

            if (keyOpt.isEmpty()) {
                timeline.add(String.format("Exhausted: No eligible keys found for model '%s'. Switching to next model in fallback chain.", currentModel));
                currentModelIdx++;
                continue;
            }

            ApiKey apiKey = keyOpt.get();
            String keyId = apiKey.getId();
            long startTime = System.currentTimeMillis();

            try {
                // Call API
                String responseText = simulateApiCall(apiKey, currentModel, prompt);
                long latency = System.currentTimeMillis() - startTime;

                int promptTokens = estimatedTokens;
                int completionTokens = responseText.length() / 4 + 10;
                int totalTokens = promptTokens + completionTokens;

                int diff = totalTokens - estimatedTokens;
                rateLimitingService.adjustTpmUsage(keyId, diff);

                timeline.add(String.format("Success: Turn %d succeeded using key '%s' and model '%s' in %dms.", 
                    globalAttempts, apiKey.getName(), currentModel, latency));

                // Log success
                retryEngineService.reportResult(keyId, currentModel, true, 200, latency, null, promptTokens, completionTokens, prompt, responseText);
                schedulerService.decrementConcurrency(keyId);

                return ProxyResponse.builder()
                        .selectedKeyId(keyId)
                        .selectedKeyName(apiKey.getName())
                        .provider("gemini")
                        .model(currentModel)
                        .responseText(responseText)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .latencyMs(latency)
                        .attempts(globalAttempts)
                        .routingTimeline(timeline)
                        .build();

            } catch (IllegalArgumentException e) {
                // Client error - do not retry, do not failover
                long latency = System.currentTimeMillis() - startTime;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);
                timeline.add(String.format("Client Error: Turn %d rejected with 400 Bad Request (%s). Aborting.", globalAttempts, e.getMessage()));
                retryEngineService.reportResult(keyId, currentModel, false, 400, latency, e.getMessage(), estimatedTokens, 0, prompt, null);
                throw e;

            } catch (Exception e) {
                // Failover trigger
                long latency = System.currentTimeMillis() - startTime;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);

                int statusCode = 500;
                String reason = e.getMessage();
                if (reason != null && reason.contains("RATE_LIMIT_ERROR")) {
                    statusCode = 429;
                } else if (reason != null && reason.contains("UNAUTHORIZED_ERROR")) {
                    statusCode = 401;
                } else if (reason != null && reason.contains("FORBIDDEN_ERROR")) {
                    statusCode = 403;
                } else if (reason != null && reason.contains("TIMEOUT_ERROR")) {
                    statusCode = 504;
                } else if (reason != null && reason.contains("PROVIDER_ERROR")) {
                    statusCode = 503;
                }

                timeline.add(String.format("Failure: Key '%s' failed on model '%s' with status %d (%s) in %dms.", 
                    apiKey.getName(), currentModel, statusCode, reason, latency));

                // Report failure
                retryEngineService.reportResult(keyId, currentModel, false, statusCode, latency, reason, estimatedTokens, 0, prompt, null);

                // Exclude key for future selection on this model
                excludedKeysMap.get(currentModel).add(keyId);

                // Determine routing action based on error code:
                if (statusCode == 503 || statusCode == 500) {
                    // 503 / 500 provider error -> fallback to next model immediately
                    timeline.add(String.format("Error %d indicates provider failure. Changing model immediately.", statusCode));
                    currentModelIdx++;
                } else {
                    // 429 rate limit or other errors -> retry with another key on same model
                    timeline.add(String.format("Error %d. Retrying other keys for model '%s'.", statusCode, currentModel));
                }
            }
        }

        String finalErr = String.format("Chatbot Exhausted: Failed to get result after %d turns across fallback chain.", globalAttempts);
        timeline.add(finalErr);
        throw new KeysExhaustedException(finalErr);
    }

    private ProxyResponse executePromptInternal(String provider, String model, String prompt, int estimatedTokens, List<String> timeline) {
        List<String> excludedKeyIds = new ArrayList<>();
        int maxRetries = 4;
        int attempts = 0;

        while (attempts < maxRetries) {
            attempts++;
            timeline.add(String.format("Attempt %d: Scanning active key pool...", attempts));

            Optional<ApiKey> keyOpt;
            synchronized (schedulerService) {
                keyOpt = schedulerService.selectKey(provider, model, estimatedTokens, excludedKeyIds);
                if (keyOpt.isPresent()) {
                    schedulerService.incrementConcurrency(keyOpt.get().getId());
                    rateLimitingService.incrementUsage(keyOpt.get().getId(), estimatedTokens);
                    timeline.add(String.format("Selection: Selected key '%s' (%s) as best candidate.", keyOpt.get().getName(), keyOpt.get().getId()));
                }
            }

            if (keyOpt.isEmpty()) {
                String errorMsg = String.format("Exhausted: No eligible keys found for %s/%s. Excluded: %s.", provider.toUpperCase(), model, excludedKeyIds);
                timeline.add(errorMsg);
                throw new KeysExhaustedException(errorMsg);
            }

            ApiKey apiKey = keyOpt.get();
            String keyId = apiKey.getId();

            long startTime = System.currentTimeMillis();

            try {
                // Simulate HTTP API Call
                String responseText = simulateApiCall(apiKey, model, prompt);
                long latency = System.currentTimeMillis() - startTime;

                int promptTokens = estimatedTokens;
                int completionTokens = responseText.length() / 4 + 10;
                int totalTokens = promptTokens + completionTokens;

                // Adjust rate limits in Redis based on actual usage
                int diff = totalTokens - estimatedTokens;
                rateLimitingService.adjustTpmUsage(keyId, diff);

                timeline.add(String.format("Execution: Key '%s' successfully completed prompt execution in %dms. Usage: %d prompt / %d completion tokens.", apiKey.getName(), latency, promptTokens, completionTokens));

                // Log success and update Redis health metrics using RetryEngineService
                retryEngineService.reportResult(keyId, model, true, 200, latency, null, promptTokens, completionTokens, prompt, responseText);

                // Decrement active connections
                schedulerService.decrementConcurrency(keyId);

                return ProxyResponse.builder()
                        .selectedKeyId(keyId)
                        .selectedKeyName(apiKey.getName())
                        .provider(provider)
                        .model(model)
                        .responseText(responseText)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .latencyMs(latency)
                        .attempts(attempts)
                        .routingTimeline(timeline)
                        .build();

            } catch (IllegalArgumentException e) {
                // Client error (400 Bad Request) - do not trigger cooldown, do not retry
                long latency = System.currentTimeMillis() - startTime;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);

                timeline.add(String.format("Client Error: Request rejected with 400 Bad Request (%s). Aborting execution without retries.", e.getMessage()));

                // Delegate error reporting to RetryEngineService
                retryEngineService.reportResult(keyId, model, false, 400, latency, e.getMessage(), estimatedTokens, 0, prompt, null);

                throw e;

            } catch (Exception e) {
                // Failover trigger (Rate limit 429 or Provider 5xx error)
                long latency = System.currentTimeMillis() - startTime;
                schedulerService.decrementConcurrency(keyId);
                rateLimitingService.refundUsage(keyId, estimatedTokens);

                int statusCode = 500;
                String reason = e.getMessage();
                if ("RATE_LIMIT_ERROR".equals(reason)) {
                    statusCode = 429;
                } else if ("UNAUTHORIZED_ERROR".equals(reason)) {
                    statusCode = 401;
                } else if ("FORBIDDEN_ERROR".equals(reason)) {
                    statusCode = 403;
                } else if ("TIMEOUT_ERROR".equals(reason)) {
                    statusCode = 504;
                }

                timeline.add(String.format("Failover: Key '%s' failed with status %d (%s) in %dms. Key excluded for retries.", apiKey.getName(), statusCode, reason, latency));

                // Delegate error reporting to RetryEngineService
                retryEngineService.reportResult(keyId, model, false, statusCode, latency, reason, estimatedTokens, 0, prompt, null);

                // Exclude key from next scheduling attempt
                excludedKeyIds.add(keyId);
            }
        }

        String finalErr = "Exhausted all available keys for " + provider + "/" + model + " after " + attempts + " failover attempts.";
        timeline.add(finalErr);
        throw new KeysExhaustedException(finalErr);
    }

    private String simulateApiCall(ApiKey apiKey, String model, String prompt) throws Exception {
        // Simulate network delay for override keywords
        if (prompt != null) {
            if (prompt.contains("trigger_rate_limit")) {
                Thread.sleep(150);
                throw new Exception("RATE_LIMIT_ERROR");
            }
            if (prompt.contains("trigger_provider_error")) {
                Thread.sleep(150);
                throw new Exception("PROVIDER_ERROR");
            }
            if (prompt.contains("trigger_client_error")) {
                Thread.sleep(150);
                throw new IllegalArgumentException("CLIENT_ERROR: Invalid parameter combination in prompt request.");
            }
            if (prompt.contains("trigger_unauthorized")) {
                Thread.sleep(150);
                throw new Exception("UNAUTHORIZED_ERROR");
            }
            if (prompt.contains("trigger_forbidden")) {
                Thread.sleep(150);
                throw new Exception("FORBIDDEN_ERROR");
            }
            if (prompt.contains("trigger_timeout")) {
                Thread.sleep(150);
                throw new Exception("TIMEOUT_ERROR");
            }
        }

        // If no test triggers are present, call actual Google Gemini API
        return makeRealApiCall(apiKey.getKeyValue(), model, prompt);
    }

    private String makeRealApiCall(String apiKeyVal, String model, String prompt) throws Exception {
        String actualModel = mapToActualGeminiModel(model);
        String urlString = String.format(
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
            actualModel, apiKeyVal
        );

        // Build the request body using Map and ObjectMapper
        java.util.Map<String, Object> textPart = java.util.Map.of("text", prompt);
        java.util.Map<String, Object> part = java.util.Map.of("parts", java.util.List.of(textPart));
        java.util.Map<String, Object> content = java.util.Map.of("contents", java.util.List.of(part));
        String jsonPayload = objectMapper.writeValueAsString(content);

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(urlString))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseBody = response.body();

        if (statusCode == 200) {
            // Parse Gemini Response to extract candidates[0].content.parts[0].text
            try {
                com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(responseBody);
                com.fasterxml.jackson.databind.JsonNode candidates = rootNode.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    com.fasterxml.jackson.databind.JsonNode parts = candidates.get(0).path("content").path("parts");
                    if (parts.isArray() && parts.size() > 0) {
                        return parts.get(0).path("text").asText();
                    }
                }
                return responseBody;
            } catch (Exception ex) {
                return "Error parsing API response: " + ex.getMessage() + "\nRaw response: " + responseBody;
            }
        } else if (statusCode == 429) {
            throw new Exception("RATE_LIMIT_ERROR");
        } else if (statusCode == 401) {
            throw new Exception("UNAUTHORIZED_ERROR");
        } else if (statusCode == 403) {
            throw new Exception("FORBIDDEN_ERROR");
        } else if (statusCode == 400) {
            throw new IllegalArgumentException("CLIENT_ERROR: " + responseBody);
        } else {
            throw new Exception("PROVIDER_ERROR: Status " + statusCode + " - " + responseBody);
        }
    }

    private String mapToActualGeminiModel(String model) {
        if (model == null) return "gemini-1.5-flash";
        String modelLower = model.toLowerCase();
        if (modelLower.contains("2.5-flash-lite")) return "gemini-2.5-flash-lite";
        if (modelLower.contains("flash-lite")) return "gemini-1.5-flash-lite";
        if (modelLower.contains("2.5-flash")) return "gemini-2.5-flash";
        if (modelLower.contains("1.5-flash")) return "gemini-1.5-flash";
        if (modelLower.contains("1.5-pro")) return "gemini-1.5-pro";
        if (modelLower.contains("3.5-flash")) return "gemini-2.5-flash";
        if (modelLower.contains("3.1-flash")) return "gemini-1.5-flash";
        return "gemini-1.5-flash";
    }
}
