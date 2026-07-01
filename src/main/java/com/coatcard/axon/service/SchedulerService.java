package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.redis.RedisPairEntry;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.strategy.SchedulingStrategy;
import com.coatcard.axon.utils.EncryptionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SchedulerService {

    private final ApiKeyRepository apiKeyRepository;
    private final RateLimitingService rateLimitingService;
    private final CooldownService cooldownService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisPairCacheService redisPairCacheService;
    private final EncryptionUtils encryptionUtils;
    private final Map<String, SchedulingStrategy> strategies;
    
    @Value("${axon.scheduler.strategy:LRU}")
    private String defaultStrategyName;

    public SchedulerService(ApiKeyRepository apiKeyRepository,
                            RateLimitingService rateLimitingService,
                            CooldownService cooldownService,
                            StringRedisTemplate stringRedisTemplate,
                            RedisPairCacheService redisPairCacheService,
                            EncryptionUtils encryptionUtils,
                            List<SchedulingStrategy> strategyList) {
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimitingService = rateLimitingService;
        this.cooldownService = cooldownService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisPairCacheService = redisPairCacheService;
        this.encryptionUtils = encryptionUtils;
        
        // Map strategies by name
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        strategy -> strategy.getName().toUpperCase(),
                        strategy -> strategy,
                        (existing, replacement) -> existing
                ));
    }

    public Optional<ApiKey> selectKey(String provider, String model, int estimatedTokens) {
        return selectKey(provider, model, estimatedTokens, Collections.emptyList());
    }

    public Optional<ApiKey> selectKey(String provider, String model, int estimatedTokens, List<String> excludedKeyIds) {
        String normalizedProvider = provider != null ? provider.toLowerCase().trim() : "";
        String normalizedModel = model != null ? model.toLowerCase().trim() : "";
        List<RedisPairEntry> candidates = redisPairCacheService.getCandidates(normalizedProvider, normalizedModel);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();

        // Filter out DISABLED, COOLDOWN, UNHEALTHY
        List<RedisPairEntry> eligible = candidates.stream()
                .filter(entry -> "ACTIVE".equalsIgnoreCase(entry.getCurrentStatus()))
                .filter(entry -> entry.getCooldownUntil() <= now)
                .filter(entry -> entry.getHealthScore() >= 0.2) // Below 0.2 health score is considered unhealthy
                .filter(entry -> !excludedKeyIds.contains(entry.getKeyId()))
                .filter(entry -> {
                    // Check rate limiting in Redis if keys are rate limited
                    // Look up actual ApiKey from DB briefly for limit checks or do in memory
                    // To keep it high performance, let's load the key object
                    Optional<ApiKey> keyOpt = apiKeyRepository.findById(entry.getKeyId());
                    return keyOpt.isPresent() && !isRateLimitedSafe(keyOpt.get(), estimatedTokens);
                })
                .collect(Collectors.toList());

        if (eligible.isEmpty()) {
            return Optional.empty();
        }

        // Apply strategy
        SchedulingStrategy strategy = getStrategy();
        Optional<RedisPairEntry> selectedPairOpt = strategy.select(eligible);

        if (selectedPairOpt.isEmpty()) {
            return Optional.empty();
        }

        RedisPairEntry selectedPair = selectedPairOpt.get();

        // Update usage in Redis
        redisPairCacheService.recordPairUsage(provider, model, selectedPair.getKeyId());

        // Load complete ApiKey details from MongoDB
        return apiKeyRepository.findById(selectedPair.getKeyId())
                .map(key -> {
                    // Decrypt API key value for final provider use
                    if (key.getApiKey() != null) {
                        key.setKeyValue(encryptionUtils.decrypt(key.getApiKey()));
                    }
                    return key;
                });
    }

    private SchedulingStrategy getStrategy() {
        String name = defaultStrategyName != null ? defaultStrategyName.toUpperCase() : "LRU";
        SchedulingStrategy strategy = strategies.get(name);
        if (strategy == null) {
            // Default fallback
            strategy = strategies.get("LRU");
        }
        return strategy;
    }

    private boolean isCooldownSafe(String keyId) {
        try {
            return cooldownService.isCooldown(keyId);
        } catch (Exception e) {
            System.err.println("Redis is down, ignoring cooldown check for key: " + keyId);
            return false;
        }
    }

    private boolean isRateLimitedSafe(ApiKey key, int tokens) {
        try {
            return rateLimitingService.isRateLimited(key, tokens);
        } catch (Exception e) {
            System.err.println("Redis is down, ignoring rate-limiting check for key: " + key.getId());
            return false;
        }
    }

    public void incrementConcurrency(String keyId) {
        try {
            String key = getConcurrencyKey(keyId);
            Long current = stringRedisTemplate.opsForValue().increment(key);
            if (current != null && current == 1) {
                stringRedisTemplate.expire(key, java.time.Duration.ofSeconds(120));
            }
        } catch (Exception e) {
            System.err.println("Redis is down, failed to increment concurrency for key: " + keyId);
        }
    }

    public void decrementConcurrency(String keyId) {
        try {
            String key = getConcurrencyKey(keyId);
            Long current = stringRedisTemplate.opsForValue().decrement(key);
            if (current != null && current < 0) {
                stringRedisTemplate.opsForValue().set(key, "0");
            }
        } catch (Exception e) {
            System.err.println("Redis is down, failed to decrement concurrency for key: " + keyId);
        }
    }

    public int getConcurrency(ApiKey apiKey) {
        try {
            String key = getConcurrencyKey(apiKey.getId());
            String val = stringRedisTemplate.opsForValue().get(key);
            return val != null ? Integer.parseInt(val) : 0;
        } catch (Exception e) {
            System.err.println("Redis is down, failed to get concurrency for key: " + apiKey.getId());
            return 0;
        }
    }


    private String getConcurrencyKey(String keyId) {
        return "concurrency:" + keyId;
    }
}
