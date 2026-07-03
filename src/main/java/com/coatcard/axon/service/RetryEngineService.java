package com.coatcard.axon.service;

import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.ApiKeyStatus;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.redis.RedisPairEntry;
import com.coatcard.axon.repository.AiModelRepository;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.UsageLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class RetryEngineService {

    private final RedisPairCacheService cacheService;
    private final ApiKeyRepository apiKeyRepository;
    private final AiModelRepository modelRepository;
    private final UsageLogRepository usageLogRepository;

    @Value("${axon.retry.cooldown-default-seconds:60}")
    private int defaultCooldownSeconds;

    @Value("${axon.retry.max-consecutive-failures:5}")
    private int maxConsecutiveFailures;

    public RetryEngineService(RedisPairCacheService cacheService,
                              ApiKeyRepository apiKeyRepository,
                              AiModelRepository modelRepository,
                              UsageLogRepository usageLogRepository) {
        this.cacheService = cacheService;
        this.apiKeyRepository = apiKeyRepository;
        this.modelRepository = modelRepository;
        this.usageLogRepository = usageLogRepository;
    }

    /**
     * Reports the result of a request and updates metadata, applying retry/cooldown rules on failure.
     */
    public void reportResult(String keyId, String modelId, boolean success, int statusCode, long latencyMs, String errorMessage) {
        reportResult(keyId, modelId, success, statusCode, latencyMs, errorMessage, 0, 0, null, null);
    }

    /**
     * Overloaded reportResult that logs prompt tokens, completion tokens, prompt content, and response text.
     */
    public void reportResult(String keyId, String modelId, boolean success, int statusCode, long latencyMs, String errorMessage,
                             int promptTokens, int completionTokens, String prompt, String responseText) {
        // Find key and model details in DB to identify the provider and model name
        Optional<ApiKey> keyOpt = apiKeyRepository.findById(keyId);
        Optional<AiModel> modelOpt = modelRepository.findById(modelId);

        if (keyOpt.isEmpty()) {
            System.err.println("Reported result for non-existent key: " + keyId);
            return;
        }

        ApiKey apiKey = keyOpt.get();
        String provider = apiKey.getProvider();
        
        // Resolve model name
        String modelName = modelOpt.map(AiModel::getName)
                .orElse(modelId); // fallback to modelId as name if not found

        // 1. Fetch pair from Redis
        RedisPairEntry entry = cacheService.getPair(provider, modelName, keyId);
        if (entry == null) {
            // Pair not in Redis, reconstruct and save it
            entry = RedisPairEntry.builder()
                    .keyId(keyId)
                    .modelId(modelId)
                    .provider(provider)
                    .currentStatus("ACTIVE")
                    .lastUsed(System.currentTimeMillis())
                    .healthScore(1.0)
                    .modelPriority(modelOpt.map(AiModel::getPriority).orElse(1))
                    .providerPriority(1)
                    .limitRpm(apiKey.getLimitRpm())
                    .limitTpm(apiKey.getLimitTpm())
                    .build();
        }
        
        if (entry.getProvider() == null) {
            entry.setProvider(provider != null ? provider : "gemini");
        }

        long now = System.currentTimeMillis();
        entry.setLastUsed(now);

        if (success) {
            entry.setSuccessCount(entry.getSuccessCount() + 1);
            entry.setFailureCount(0); // reset consecutive failure count
            
            // Recalculate healthScore
            double total = entry.getSuccessCount() + entry.getFailureCount();
            entry.setHealthScore(total > 0 ? (double) entry.getSuccessCount() / total : 1.0);
            
            // If it was in cooldown or unhealthy, restore status
            if ("COOLDOWN".equalsIgnoreCase(entry.getCurrentStatus()) || "UNHEALTHY".equalsIgnoreCase(entry.getCurrentStatus())) {
                entry.setCurrentStatus("ACTIVE");
            }
            
            cacheService.savePair(entry, modelName);
        } else {
            entry.setFailureCount(entry.getFailureCount() + 1);
            double total = entry.getSuccessCount() + entry.getFailureCount();
            entry.setHealthScore(total > 0 ? (double) entry.getSuccessCount() / total : 0.0);

            // Handle failures according to spec
            if (statusCode == 429) {
                // Rate limited: Cooldown key
                int cooldownSec = apiKey.getCooldownDurationSeconds() > 0 ? apiKey.getCooldownDurationSeconds() : defaultCooldownSeconds;
                entry.setCurrentStatus("COOLDOWN");
                entry.setCooldownUntil(now + (cooldownSec * 1000L));
                cacheService.savePair(entry, modelName);
                System.out.println("API Key " + keyId + " rate-limited. Putting in COOLDOWN for " + cooldownSec + " seconds.");

            } else if (statusCode == 401) {
                // Unauthorized / Invalid Key: Disable key permanently
                apiKey.setActive(false);
                apiKey.setStatus(ApiKeyStatus.DISABLED);
                apiKeyRepository.save(apiKey);

                // Remove key mappings from Redis cache entirely
                evictAllPairsForKey(apiKey);
                System.out.println("API Key " + keyId + " returned 401. Permanently DISABLED key in DB and evicted from Redis.");

            } else if (statusCode == 403) {
                // Forbidden: Put in cooldown rather than permanently disabling
                int cooldownSec = apiKey.getCooldownDurationSeconds() > 0 ? apiKey.getCooldownDurationSeconds() : defaultCooldownSeconds;
                entry.setCurrentStatus("COOLDOWN");
                entry.setCooldownUntil(now + (cooldownSec * 1000L));
                cacheService.savePair(entry, modelName);
                System.out.println("API Key " + keyId + " returned 403 (Forbidden) for model " + modelName + ". Putting in COOLDOWN for " + cooldownSec + " seconds.");

            } else {
                // Provider errors (500/503/Timeout) or other unknown errors
                if (entry.getFailureCount() >= maxConsecutiveFailures) {
                    entry.setCurrentStatus("UNHEALTHY");
                    entry.setHealthScore(0.0); // force health score to 0 to deprioritize
                }
                cacheService.savePair(entry, modelName);
                System.out.println("API Key " + keyId + " encountered error. Status code: " + statusCode + ". Consecutive failure count: " + entry.getFailureCount());
            }
        }

        // Log usage log to Mongo
        UsageLog log = UsageLog.builder()
                .keyId(keyId)
                .keyName(apiKey.getName())
                .provider(provider)
                .model(modelName)
                .modelId(modelId)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .latency(latencyMs)
                .latencyMs(latencyMs)
                .success(success)
                .status(success ? "SUCCESS" : translateStatusCode(statusCode))
                .errorCode(String.valueOf(statusCode))
                .errorMessage(errorMessage)
                .prompt(prompt)
                .responseText(responseText)
                .timestamp(Instant.now())
                .requestTime(Instant.now())
                .build();
        
        usageLogRepository.save(log);
    }

    private void evictAllPairsForKey(ApiKey apiKey) {
        // Evict key from all models in Redis
        List<AiModel> allModels = modelRepository.findAll();
        for (AiModel model : allModels) {
            cacheService.evictPair(apiKey.getProvider(), model.getName(), apiKey.getId());
        }
    }

    private String translateStatusCode(int statusCode) {
        return switch (statusCode) {
            case 429 -> "RATE_LIMIT_ERROR";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 400 -> "CLIENT_ERROR";
            default -> "PROVIDER_ERROR";
        };
    }
}
