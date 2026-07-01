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

    public ProxyService(SchedulerService schedulerService,
                        RateLimitingService rateLimitingService,
                        CooldownService cooldownService,
                        UsageLogRepository usageLogRepository) {
        this.schedulerService = schedulerService;
        this.rateLimitingService = rateLimitingService;
        this.cooldownService = cooldownService;
        this.usageLogRepository = usageLogRepository;
    }

    public ProxyResponse executePrompt(String provider, String model, String prompt, int estimatedTokens) {
        List<String> excludedKeyIds = new ArrayList<>();
        int maxRetries = 4;
        int attempts = 0;

        while (attempts < maxRetries) {
            attempts++;
            Optional<ApiKey> keyOpt = schedulerService.selectKey(provider, model, estimatedTokens, excludedKeyIds);

            if (keyOpt.isEmpty()) {
                throw new KeysExhaustedException("No available keys for " + provider + "/" + model 
                        + " (all keys are exhausted, rate-limited, or in cooldown).");
            }

            ApiKey apiKey = keyOpt.get();
            String keyId = apiKey.getId();

            // Increment active connections
            schedulerService.incrementConcurrency(keyId);
            long startTime = System.currentTimeMillis();

            try {
                // Simulate HTTP API Call
                String responseText = simulateApiCall(apiKey, model, prompt);
                long latency = System.currentTimeMillis() - startTime;

                int promptTokens = estimatedTokens;
                int completionTokens = responseText.length() / 4 + 10;
                int totalTokens = promptTokens + completionTokens;

                // Update rate limits in Redis
                rateLimitingService.incrementUsage(keyId, totalTokens);

                // Log success in DB
                UsageLog log = UsageLog.builder()
                        .keyId(keyId)
                        .keyName(apiKey.getName())
                        .provider(provider)
                        .model(model)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .latencyMs(latency)
                        .status("SUCCESS")
                        .timestamp(Instant.now())
                        .build();
                usageLogRepository.save(log);

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
                        .build();

            } catch (IllegalArgumentException e) {
                // Client error (400 Bad Request) - do not trigger cooldown, do not retry
                long latency = System.currentTimeMillis() - startTime;
                schedulerService.decrementConcurrency(keyId);

                UsageLog log = UsageLog.builder()
                        .keyId(keyId)
                        .keyName(apiKey.getName())
                        .provider(provider)
                        .model(model)
                        .promptTokens(estimatedTokens)
                        .completionTokens(0)
                        .latencyMs(latency)
                        .status("CLIENT_ERROR")
                        .errorMessage(e.getMessage())
                        .timestamp(Instant.now())
                        .build();
                usageLogRepository.save(log);

                throw e;

            } catch (Exception e) {
                // Failover trigger (Rate limit 429 or Provider 5xx error)
                long latency = System.currentTimeMillis() - startTime;
                schedulerService.decrementConcurrency(keyId);

                String errorType = "PROVIDER_ERROR";
                String reason = e.getMessage();
                if ("RATE_LIMIT_ERROR".equals(reason)) {
                    errorType = "RATE_LIMIT_ERROR";
                }

                // Trigger cooldown in Redis
                int cooldownSec = apiKey.getCooldownDurationSeconds() > 0 ? apiKey.getCooldownDurationSeconds() : 30;
                cooldownService.triggerCooldown(keyId, errorType + ": " + reason, cooldownSec);

                // Log failover attempt in DB
                UsageLog log = UsageLog.builder()
                        .keyId(keyId)
                        .keyName(apiKey.getName())
                        .provider(provider)
                        .model(model)
                        .promptTokens(estimatedTokens)
                        .completionTokens(0)
                        .latencyMs(latency)
                        .status(errorType)
                        .errorMessage(reason)
                        .timestamp(Instant.now())
                        .build();
                usageLogRepository.save(log);

                // Exclude key from next scheduling attempt
                excludedKeyIds.add(keyId);
            }
        }

        throw new KeysExhaustedException("Exhausted all available keys for " + provider + "/" + model 
                + " after " + attempts + " failover attempts.");
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
        }

        return String.format(
                "[Response from provider: %s, model: %s]\n" +
                "Successfully processed prompt using key: '%s'.\n" +
                "Prompt details: '%s'",
                apiKey.getProvider().toUpperCase(), model, apiKey.getName(), prompt
        );
    }
}
