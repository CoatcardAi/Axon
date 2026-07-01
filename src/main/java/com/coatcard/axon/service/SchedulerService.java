package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.repository.ApiKeyRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SchedulerService {

    private final ApiKeyRepository apiKeyRepository;
    private final RateLimitingService rateLimitingService;
    private final CooldownService cooldownService;
    private final StringRedisTemplate stringRedisTemplate;

    public SchedulerService(ApiKeyRepository apiKeyRepository,
                            RateLimitingService rateLimitingService,
                            CooldownService cooldownService,
                            StringRedisTemplate stringRedisTemplate) {
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimitingService = rateLimitingService;
        this.cooldownService = cooldownService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Optional<ApiKey> selectKey(String provider, String model, int estimatedTokens) {
        return selectKey(provider, model, estimatedTokens, java.util.Collections.emptyList());
    }

    public Optional<ApiKey> selectKey(String provider, String model, int estimatedTokens, List<String> excludedKeyIds) {
        String normalizedProvider = provider != null ? provider.toLowerCase().trim() : "";
        String normalizedModel = model != null ? model.toLowerCase().trim() : "";
        List<ApiKey> eligibleKeys = apiKeyRepository.findByProviderAndModelsContainingAndActiveTrue(normalizedProvider, normalizedModel);

        if (eligibleKeys.isEmpty()) {
            return Optional.empty();
        }

        List<ApiKey> availableKeys = eligibleKeys.stream()
                .filter(key -> !excludedKeyIds.contains(key.getId()))
                .filter(key -> !isCooldownSafe(key.getId()))
                .filter(key -> !isRateLimitedSafe(key, estimatedTokens))
                .collect(Collectors.toList());

        if (availableKeys.isEmpty()) {
            return Optional.empty();
        }

        return availableKeys.stream()
                .min(Comparator
                        .comparingInt(this::getConcurrency)
                        .thenComparingDouble(key -> -getCapacityHeadroomRatio(key))
                );
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
            stringRedisTemplate.opsForValue().increment(getConcurrencyKey(keyId));
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

    private double getCapacityHeadroomRatio(ApiKey apiKey) {
        try {
            int limitRpm = apiKey.getLimitRpm();
            int limitTpm = apiKey.getLimitTpm();

            double rpmRatio = limitRpm > 0 ? (double) rateLimitingService.getRemainingRpm(apiKey) / limitRpm : 1.0;
            double tpmRatio = limitTpm > 0 ? (double) rateLimitingService.getRemainingTpm(apiKey) / limitTpm : 1.0;

            return Math.min(rpmRatio, tpmRatio);
        } catch (Exception e) {
            System.err.println("Redis is down, returning default headroom ratio for key: " + apiKey.getId());
            return 1.0;
        }
    }

    private String getConcurrencyKey(String keyId) {
        return "concurrency:" + keyId;
    }
}
