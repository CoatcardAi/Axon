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

    // Key naming:
    //   pair hash:  "pair:{provider}:{modelName}:{keyId}"
    //   index set:  "idx:{provider}:{modelName}"   -> members: keyId values
    //   global set: "pair:all"                      -> members: full pair-key strings

    private static final String PAIR_PREFIX = "pair:";
    private static final String IDX_PREFIX  = "idx:";
    private static final String GLOBAL_IDX  = "pair:all";

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

    // ──────────────────────────────────────────────────────────────────────────
    // Cache Warmup
    // ──────────────────────────────────────────────────────────────────────────

    public void warmupCache() {
        List<ApiKey> activeKeys = apiKeyRepository.findAll().stream()
                .filter(key -> key.isActive() || key.getStatus() == ApiKeyStatus.ACTIVE)
                .collect(Collectors.toList());

        List<AiModel> enabledModels = modelRepository.findAll().stream()
                .filter(model -> model.isActive() || model.isEnabled())
                .collect(Collectors.toList());

        List<KeyModelMapping> mappings = mappingRepository.findAll();

        int loadedCount = 0;
        Set<String> validPairKeys = new HashSet<>();

        for (ApiKey key : activeKeys) {
            List<AiModel> keyModels = enabledModels.stream()
                    .filter(model -> model.getProvider().equalsIgnoreCase(key.getProvider()))
                    .filter(model -> {
                        boolean hasExplicitMapping = mappings.stream()
                                .anyMatch(m -> m.getKeyId().equals(key.getId()) && m.getModelId().equals(model.getId()));
                        if (hasExplicitMapping) return true;
                        boolean inAllowed = key.getAllowedModels() != null && key.getAllowedModels().contains(model.getName());
                        boolean inLegacy  = key.getModels()        != null && key.getModels().contains(model.getName());
                        return inAllowed || inLegacy;
                    })
                    .collect(Collectors.toList());

            for (AiModel model : keyModels) {
                String pairKey = getPairKey(key.getProvider(), model.getName(), key.getId());
                String idxKey  = getIdxKey(key.getProvider(), model.getName());
                validPairKeys.add(pairKey);

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
                        .providerPriority(1)
                        .limitRpm(key.getLimitRpm())
                        .limitTpm(key.getLimitTpm())
                        .build();

                // Preserve live stats from Redis if entry already exists
                Map<Object, Object> existing = redisTemplate.opsForHash().entries(pairKey);
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

                // Write pair hash
                redisTemplate.opsForHash().putAll(pairKey, entry.toMap());
                redisTemplate.expire(pairKey, 4, TimeUnit.HOURS);

                // Add keyId to the provider:model index set
                if (redisTemplate.opsForSet() != null) {
                    redisTemplate.opsForSet().add(idxKey, key.getId());
                    redisTemplate.expire(idxKey, 4, TimeUnit.HOURS);

                    // Add to global index
                    redisTemplate.opsForSet().add(GLOBAL_IDX, pairKey);
                }

                loadedCount++;
            }
        }

        // Clean stale entries from global index and remove their hashes
        Set<String> globalMembers = (redisTemplate.opsForSet() != null)
                ? redisTemplate.opsForSet().members(GLOBAL_IDX)
                : null;
        if (globalMembers != null) {
            for (String cachedKey : globalMembers) {
                if (!validPairKeys.contains(cachedKey)) {
                    redisTemplate.delete(cachedKey);
                    redisTemplate.opsForSet().remove(GLOBAL_IDX, (Object) cachedKey);
                }
            }
        }

        System.out.println("Cache warmed up successfully. Loaded " + loadedCount + " active pairs into Redis. Cleaned up stale entries.");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Candidate Lookup — O(1) via index SET, then pipelined HGETALL
    // ──────────────────────────────────────────────────────────────────────────

    public List<RedisPairEntry> getCandidates(String provider, String modelName) {
        if (redisTemplate.opsForSet() == null) {
            return Collections.emptyList();
        }
        String idxKey = getIdxKey(provider, modelName);
        Set<String> keyIds = redisTemplate.opsForSet().members(idxKey);

        if (keyIds == null || keyIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<RedisPairEntry> candidates = new ArrayList<>(keyIds.size());
        for (String keyId : keyIds) {
            String pairKey = getPairKey(provider, modelName, keyId);
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(pairKey);
            if (!fields.isEmpty()) {
                RedisPairEntry entry = RedisPairEntry.fromMap(fields);
                if (entry != null) {
                    candidates.add(entry);
                }
            }
        }
        return candidates;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CRUD helpers
    // ──────────────────────────────────────────────────────────────────────────

    public void recordPairUsage(String provider, String modelName, String keyId) {
        String pairKey = getPairKey(provider, modelName, keyId);
        redisTemplate.opsForHash().put(pairKey, "lastUsed", String.valueOf(System.currentTimeMillis()));
        redisTemplate.expire(pairKey, 4, TimeUnit.HOURS);
    }

    public void savePair(RedisPairEntry entry, String modelName) {
        String pairKey = getPairKey(entry.getProvider(), modelName, entry.getKeyId());
        redisTemplate.opsForHash().putAll(pairKey, entry.toMap());
        redisTemplate.expire(pairKey, 4, TimeUnit.HOURS);

        // Ensure the key is still in the index (it may have been missing)
        if (redisTemplate.opsForSet() != null) {
            String idxKey = getIdxKey(entry.getProvider(), modelName);
            redisTemplate.opsForSet().add(idxKey, entry.getKeyId());
            redisTemplate.expire(idxKey, 4, TimeUnit.HOURS);
            redisTemplate.opsForSet().add(GLOBAL_IDX, pairKey);
        }
    }

    public void evictPair(String provider, String modelName, String keyId) {
        String pairKey = getPairKey(provider, modelName, keyId);
        redisTemplate.delete(pairKey);
        if (redisTemplate.opsForSet() != null) {
            redisTemplate.opsForSet().remove(getIdxKey(provider, modelName), (Object) keyId);
            redisTemplate.opsForSet().remove(GLOBAL_IDX, (Object) pairKey);
        }
    }

    public RedisPairEntry getPair(String provider, String modelName, String keyId) {
        String pairKey = getPairKey(provider, modelName, keyId);
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(pairKey);
        return fields.isEmpty() ? null : RedisPairEntry.fromMap(fields);
    }

    public Set<String> getAllPairKeys() {
        if (redisTemplate.opsForSet() == null) {
            return Collections.emptySet();
        }
        return redisTemplate.opsForSet().members(GLOBAL_IDX);
    }

    public List<RedisPairEntry> getAllPairs() {
        Set<String> keys = getAllPairKeys();
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<RedisPairEntry> entries = new ArrayList<>(keys.size());
        for (String key : keys) {
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(key);
            if (!fields.isEmpty()) {
                RedisPairEntry entry = RedisPairEntry.fromMap(fields);
                if (entry != null) entries.add(entry);
            }
        }
        return entries;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Key naming helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String getPairKey(String provider, String modelName, String keyId) {
        String p = provider  != null ? provider.toLowerCase()   : "gemini";
        String m = modelName != null ? modelName.toLowerCase()  : "";
        return PAIR_PREFIX + p + ":" + m + ":" + keyId;
    }

    private String getIdxKey(String provider, String modelName) {
        String p = provider  != null ? provider.toLowerCase()  : "gemini";
        String m = modelName != null ? modelName.toLowerCase() : "";
        return IDX_PREFIX + p + ":" + m;
    }

    // Legacy compat — used by RetryEngineService
    public String getRedisKey(String provider, String modelName, String keyId) {
        return getPairKey(provider, modelName, keyId);
    }
}
