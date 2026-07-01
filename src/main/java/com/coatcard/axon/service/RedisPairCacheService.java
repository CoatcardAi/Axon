package com.coatcard.axon.service;

import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.ApiKeyStatus;
import com.coatcard.axon.model.KeyModelMapping;
import com.coatcard.axon.redis.RedisPairEntry;
import com.coatcard.axon.repository.AiModelRepository;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.KeyModelMappingRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class RedisPairCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ApiKeyRepository apiKeyRepository;
    private final AiModelRepository modelRepository;
    private final KeyModelMappingRepository mappingRepository;

    public RedisPairCacheService(StringRedisTemplate redisTemplate,
                                 ApiKeyRepository apiKeyRepository,
                                 AiModelRepository modelRepository,
                                 KeyModelMappingRepository mappingRepository) {
        this.redisTemplate = redisTemplate;
        this.apiKeyRepository = apiKeyRepository;
        this.modelRepository = modelRepository;
        this.mappingRepository = mappingRepository;
    }

    /**
     * Load all active key-model pairs from MongoDB into Redis.
     */
    public void warmupCache() {
        List<ApiKey> activeKeys = apiKeyRepository.findAll().stream()
                .filter(key -> key.isActive() || key.getStatus() == ApiKeyStatus.ACTIVE)
                .collect(Collectors.toList());

        List<AiModel> enabledModels = modelRepository.findAll().stream()
                .filter(model -> model.isActive() || model.isEnabled())
                .collect(Collectors.toList());

        List<KeyModelMapping> mappings = mappingRepository.findAll();

        int loadedCount = 0;

        for (ApiKey key : activeKeys) {
            // Find mapped models for this key
            List<AiModel> keyModels = enabledModels.stream()
                    .filter(model -> model.getProvider().equalsIgnoreCase(key.getProvider()))
                    .filter(model -> {
                        // Mapped if there's an explicit KeyModelMapping entry
                        boolean hasExplicitMapping = mappings.stream()
                                .anyMatch(m -> m.getKeyId().equals(key.getId()) && m.getModelId().equals(model.getId()));
                        if (hasExplicitMapping) return true;

                        // Or if the model is in the key's allowedModels or legacy models list
                        boolean inAllowed = key.getAllowedModels() != null && key.getAllowedModels().contains(model.getName());
                        boolean inLegacy = key.getModels() != null && key.getModels().contains(model.getName());
                        return inAllowed || inLegacy;
                    })
                    .collect(Collectors.toList());

            for (AiModel model : keyModels) {
                String redisKey = getRedisKey(key.getProvider(), model.getName(), key.getId());
                
                // Construct entry
                RedisPairEntry entry = RedisPairEntry.builder()
                        .keyId(key.getId())
                        .modelId(model.getId())
                        .provider(key.getProvider())
                        .currentStatus("ACTIVE")
                        .lastUsed(0L)
                        .failureCount(0)
                        .successCount(0)
                        .cooldownUntil(0L)
                        .healthScore(1.0)
                        .modelPriority(model.getPriority())
                        .providerPriority(1) // Default provider priority
                        .build();

                // Check if already exists, if so keep stats but update priorities
                Map<Object, Object> existing = redisTemplate.opsForHash().entries(redisKey);
                if (!existing.isEmpty()) {
                    RedisPairEntry current = RedisPairEntry.fromMap(existing);
                    if (current != null) {
                        entry.setLastUsed(current.getLastUsed());
                        entry.setFailureCount(current.getFailureCount());
                        entry.setSuccessCount(current.getSuccessCount());
                        entry.setCooldownUntil(current.getCooldownUntil());
                        entry.setHealthScore(current.getHealthScore());
                        entry.setCurrentStatus(current.getCurrentStatus());
                    }
                }

                redisTemplate.opsForHash().putAll(redisKey, entry.toMap());
                redisTemplate.expire(redisKey, 4, TimeUnit.HOURS);
                loadedCount++;
            }
        }
        System.out.println("Cache warmed up successfully. Loaded " + loadedCount + " active pairs into Redis.");
    }

    /**
     * Get candidate pairs matching the provider and model name.
     */
    public List<RedisPairEntry> getCandidates(String provider, String modelName) {
        String pattern = provider + ":" + modelName + ":*";
        Set<String> keys = redisTemplate.keys(pattern);

        if (keys == null || keys.isEmpty()) {
            // Trigger automatic reload if empty
            warmupCache();
            keys = redisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                return Collections.emptyList();
            }
        }

        List<RedisPairEntry> candidates = new ArrayList<>();
        for (String key : keys) {
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(key);
            if (!fields.isEmpty()) {
                RedisPairEntry entry = RedisPairEntry.fromMap(fields);
                if (entry != null) {
                    candidates.add(entry);
                }
            }
        }
        return candidates;
    }

    /**
     * Refresh the TTL and lastUsed timestamp of a pair in Redis.
     */
    public void recordPairUsage(String provider, String modelName, String keyId) {
        String redisKey = getRedisKey(provider, modelName, keyId);
        redisTemplate.opsForHash().put(redisKey, "lastUsed", String.valueOf(System.currentTimeMillis()));
        redisTemplate.expire(redisKey, 4, TimeUnit.HOURS);
    }

    /**
     * Save/Update pair entry in Redis.
     */
    public void savePair(RedisPairEntry entry, String modelName) {
        String redisKey = getRedisKey(entry.getProvider(), modelName, entry.getKeyId());
        redisTemplate.opsForHash().putAll(redisKey, entry.toMap());
        redisTemplate.expire(redisKey, 4, TimeUnit.HOURS);
    }

    /**
     * Evict a pair from Redis.
     */
    public void evictPair(String provider, String modelName, String keyId) {
        String redisKey = getRedisKey(provider, modelName, keyId);
        redisTemplate.delete(redisKey);
    }

    /**
     * Fetch all keys in Redis representing key-model pairs.
     */
    public Set<String> getAllPairKeys() {
        return redisTemplate.keys("*:*:*");
    }

    /**
     * Helper to load single pair entries.
     */
    public RedisPairEntry getPair(String provider, String modelName, String keyId) {
        String redisKey = getRedisKey(provider, modelName, keyId);
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(redisKey);
        return fields.isEmpty() ? null : RedisPairEntry.fromMap(fields);
    }

    private String getRedisKey(String provider, String modelName, String keyId) {
        return provider.toLowerCase() + ":" + modelName.toLowerCase() + ":" + keyId;
    }
}
