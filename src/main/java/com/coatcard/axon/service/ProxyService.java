package com.coatcard.axon.service;

import com.coatcard.axon.dto.ProxyResponse;
import com.coatcard.axon.exception.KeysExhaustedException;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.repository.UsageLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ProxyService {

    private final SchedulerService schedulerService;
    private final RateLimitingService rateLimitingService;
    private final CooldownService cooldownService;
    private final UsageLogRepository usageLogRepository;
    private final RetryEngineService retryEngineService;

    public ProxyService(SchedulerService schedulerService,
                        RateLimitingService rateLimitingService,
                        CooldownService cooldownService,
                        UsageLogRepository usageLogRepository,
                        RetryEngineService retryEngineService) {
        this.schedulerService = schedulerService;
        this.rateLimitingService = rateLimitingService;
        this.cooldownService = cooldownService;
        this.usageLogRepository = usageLogRepository;
        this.retryEngineService = retryEngineService;
    }

    public ProxyResponse executePrompt(String provider, String model, String prompt, int estimatedTokens) {
        List<String> excludedKeyIds = new ArrayList<>();
        List<String> timeline = new ArrayList<>();
        int maxRetries = 4;
        int attempts = 0;

        timeline.add(String.format("Interception: Received request for provider '%s' and model '%s'.", provider.toUpperCase(), model));

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
        // Simulate network delay
        Thread.sleep(150 + (long) (Math.random() * 200));

        if (prompt != null) {
            if (prompt.contains("trigger_rate_limit")) {
                throw new Exception("RATE_LIMIT_ERROR");
            }
            if (prompt.contains("trigger_provider_error")) {
                throw new Exception("PROVIDER_ERROR");
            }
            if (prompt.contains("trigger_client_error")) {
                throw new IllegalArgumentException("CLIENT_ERROR: Invalid parameter combination in prompt request.");
            }
            if (prompt.contains("trigger_unauthorized")) {
                throw new Exception("UNAUTHORIZED_ERROR");
            }
            if (prompt.contains("trigger_forbidden")) {
                throw new Exception("FORBIDDEN_ERROR");
            }
            if (prompt.contains("trigger_timeout")) {
                throw new Exception("TIMEOUT_ERROR");
            }
        }

        return String.format(
                "[Response from provider: %s, model: %s]\n" +
                "Successfully processed prompt using key: '%s'.\n" +
                "Prompt details: '%s'",
                apiKey.getProvider().toUpperCase(), model, apiKey.getName(), prompt
        );
    }
}
