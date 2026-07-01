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
        List<ApiKey> eligibleKeys = apiKeyRepository.findByProviderAndModelsContainingAndActiveTrue(provider, model);

        if (eligibleKeys.isEmpty()) {
            return Optional.empty();
        }

        List<ApiKey> availableKeys = eligibleKeys.stream()
                .filter(key -> !excludedKeyIds.contains(key.getId()))
                .filter(key -> !cooldownService.isCooldown(key.getId()))
                .filter(key -> !rateLimitingService.isRateLimited(key, estimatedTokens))
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

    public void incrementConcurrency(String keyId) {
        stringRedisTemplate.opsForValue().increment(getConcurrencyKey(keyId));
    }

    public void decrementConcurrency(String keyId) {
        String key = getConcurrencyKey(keyId);
        String val = stringRedisTemplate.opsForValue().get(key);
        if (val != null && Integer.parseInt(val) > 0) {
            stringRedisTemplate.opsForValue().decrement(key);
        }
    }

    public int getConcurrency(ApiKey apiKey) {
        String key = getConcurrencyKey(apiKey.getId());
        String val = stringRedisTemplate.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : 0;
    }

    private double getCapacityHeadroomRatio(ApiKey apiKey) {
        int limitRpm = apiKey.getLimitRpm();
        int limitTpm = apiKey.getLimitTpm();

        double rpmRatio = limitRpm > 0 ? (double) rateLimitingService.getRemainingRpm(apiKey) / limitRpm : 1.0;
        double tpmRatio = limitTpm > 0 ? (double) rateLimitingService.getRemainingTpm(apiKey) / limitTpm : 1.0;

        return Math.min(rpmRatio, tpmRatio);
    }

    private String getConcurrencyKey(String keyId) {
        return "concurrency:" + keyId;
    }
}
