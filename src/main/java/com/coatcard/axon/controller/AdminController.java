package com.coatcard.axon.controller;

import com.coatcard.axon.dto.KeyHealthDetail;
import com.coatcard.axon.dto.SystemHealthResponse;
import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.repository.UsageLogRepository;
import com.coatcard.axon.service.ApiKeyService;
import com.coatcard.axon.service.CooldownService;
import com.coatcard.axon.service.ModelService;
import com.coatcard.axon.service.RateLimitingService;
import com.coatcard.axon.service.SchedulerService;
import com.coatcard.axon.service.SchedulerService;
import com.coatcard.axon.service.RedisPairCacheService;
import com.coatcard.axon.repository.KeyModelMappingRepository;
import com.coatcard.axon.model.KeyModelMapping;
import com.coatcard.axon.redis.RedisPairEntry;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final ModelService modelService;
    private final CooldownService cooldownService;
    private final RateLimitingService rateLimitingService;
    private final SchedulerService schedulerService;
    private final UsageLogRepository usageLogRepository;
    private final KeyModelMappingRepository mappingRepository;
    private final RedisPairCacheService redisPairCacheService;
    private final StringRedisTemplate stringRedisTemplate;

    public AdminController(ApiKeyService apiKeyService,
                           ModelService modelService,
                           CooldownService cooldownService,
                           RateLimitingService rateLimitingService,
                           SchedulerService schedulerService,
                           UsageLogRepository usageLogRepository,
                           KeyModelMappingRepository mappingRepository,
                           RedisPairCacheService redisPairCacheService,
                           StringRedisTemplate stringRedisTemplate) {
        this.apiKeyService = apiKeyService;
        this.modelService = modelService;
        this.cooldownService = cooldownService;
        this.rateLimitingService = rateLimitingService;
        this.schedulerService = schedulerService;
        this.usageLogRepository = usageLogRepository;
        this.mappingRepository = mappingRepository;
        this.redisPairCacheService = redisPairCacheService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // --- Key Management ---

    @GetMapping("/keys")
    public ResponseEntity<List<ApiKey>> getAllKeys() {
        List<ApiKey> keys = apiKeyService.getAllKeys().stream()
                .map(this::maskKeyCopy)
                .collect(Collectors.toList());
        return ResponseEntity.ok(keys);
    }

    @GetMapping("/keys/{id}")
    public ResponseEntity<ApiKey> getKeyById(@PathVariable String id) {
        return apiKeyService.getKeyById(id)
                .map(this::maskKeyCopy)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/keys")
    public ResponseEntity<ApiKey> createKey(@Valid @RequestBody ApiKey apiKey) {
        ApiKey created = apiKeyService.createKey(apiKey);
        tryWarmupCache();
        return ResponseEntity.ok(maskKeyCopy(created));
    }

    @PutMapping("/keys/{id}")
    public ResponseEntity<ApiKey> updateKey(@PathVariable String id, @Valid @RequestBody ApiKey apiKeyDetails) {
        try {
            ApiKey updated = apiKeyService.updateKey(id, apiKeyDetails);
            tryWarmupCache();
            return ResponseEntity.ok(maskKeyCopy(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> deleteKey(@PathVariable String id) {
        apiKeyService.deleteKey(id);
        mappingRepository.deleteByKeyId(id);
        tryWarmupCache();
        return ResponseEntity.noContent().build();
    }

    // --- Cooldown Overrides ---

    @PostMapping("/keys/{id}/cooldown")
    public ResponseEntity<String> triggerCooldown(
            @PathVariable String id,
            @RequestParam String reason,
            @RequestParam int durationSeconds) {
        apiKeyService.getKeyById(id)
                .orElseThrow(() -> new IllegalArgumentException("Key not found with id: " + id));

        cooldownService.triggerCooldown(id, "ADMIN_OVERRIDE: " + reason, durationSeconds);
        return ResponseEntity.ok("Key put in cooldown for " + durationSeconds + " seconds.");
    }

    @DeleteMapping("/keys/{id}/cooldown")
    public ResponseEntity<String> clearCooldown(@PathVariable String id) {
        apiKeyService.getKeyById(id)
                .orElseThrow(() -> new IllegalArgumentException("Key not found with id: " + id));

        cooldownService.clearCooldown(id);
        return ResponseEntity.ok("Cooldown cleared for key.");
    }

    // --- Model Management ---

    @GetMapping("/models")
    public ResponseEntity<List<AiModel>> getAllModels() {
        return ResponseEntity.ok(modelService.getAllModels());
    }

    @PostMapping("/models")
    public ResponseEntity<AiModel> createModel(@Valid @RequestBody AiModel model) {
        AiModel created = modelService.createModel(model);
        tryWarmupCache();
        return ResponseEntity.ok(created);
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<AiModel> updateModel(@PathVariable String id, @Valid @RequestBody AiModel modelDetails) {
        try {
            AiModel updated = modelService.updateModel(id, modelDetails);
            tryWarmupCache();
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable String id) {
        modelService.deleteModel(id);
        tryWarmupCache();
        return ResponseEntity.noContent().build();
    }

    // --- System Health and Statistics ---

    @GetMapping("/health")
    public ResponseEntity<SystemHealthResponse> getSystemHealth() {
        List<ApiKey> allKeys = apiKeyService.getAllKeys();

        int totalKeys = allKeys.size();
        int activeKeys = 0;
        int cooldownKeys = 0;
        int inactiveKeys = 0;

        List<KeyHealthDetail> details = new ArrayList<>();
        List<RedisPairEntry> allPairs = new ArrayList<>();
        String redisStatus = "CONNECTED";
        int redisCachedPairsCount = 0;

        try {
            allPairs = redisPairCacheService.getAllPairs();
            redisCachedPairsCount = allPairs.size();
            String ping = stringRedisTemplate.getConnectionFactory().getConnection().ping();
            if (!"PONG".equalsIgnoreCase(ping)) {
                redisStatus = "DISCONNECTED";
            }
        } catch (Exception e) {
            redisStatus = "DISCONNECTED";
        }

        String mongoStatus = "CONNECTED";
        String mongoSyncStatus = "SYNCHRONIZED";
        try {
            long keysInDb = allKeys.size();
            if (keysInDb > 0 && redisCachedPairsCount == 0 && "CONNECTED".equals(redisStatus)) {
                mongoSyncStatus = "OUT_OF_SYNC";
            }
        } catch (Exception e) {
            mongoStatus = "DISCONNECTED";
            mongoSyncStatus = "UNKNOWN";
        }

        for (ApiKey key : allKeys) {
            boolean inCooldown = cooldownService.isCooldown(key.getId());
            int concurrency = schedulerService.getConcurrency(key);
            int remainingRpm = rateLimitingService.getRemainingRpm(key);
            int remainingTpm = rateLimitingService.getRemainingTpm(key);

            if (!key.isActive()) {
                inactiveKeys++;
            } else if (inCooldown) {
                cooldownKeys++;
            } else {
                activeKeys++;
            }

            final String kid = key.getId();
            List<RedisPairEntry> keyPairs = allPairs.stream()
                    .filter(p -> p.getKeyId().equals(kid))
                    .collect(Collectors.toList());

            int successCount = keyPairs.stream().mapToInt(RedisPairEntry::getSuccessCount).sum();
            int failureCount = keyPairs.stream().mapToInt(RedisPairEntry::getFailureCount).sum();
            long lastUsed = keyPairs.stream().mapToLong(RedisPairEntry::getLastUsed).max().orElse(0L);
            double avgHealthScore = keyPairs.isEmpty() ? 1.0 : keyPairs.stream().mapToDouble(RedisPairEntry::getHealthScore).average().orElse(1.0);

            details.add(KeyHealthDetail.builder()
                    .id(key.getId())
                    .name(key.getName())
                    .provider(key.getProvider())
                    .currentConcurrency(concurrency)
                    .inCooldown(inCooldown)
                    .remainingCooldownSeconds(inCooldown ? cooldownService.getCooldownTtl(key.getId()) : 0L)
                    .cooldownReason(inCooldown ? cooldownService.getCooldownReason(key.getId()) : null)
                    .remainingRpm(remainingRpm)
                    .remainingTpm(remainingTpm)
                    .healthScore(avgHealthScore)
                    .successCount(successCount)
                    .failureCount(failureCount)
                    .lastUsed(lastUsed)
                    .build());
        }

        SystemHealthResponse health = SystemHealthResponse.builder()
                .totalKeys(totalKeys)
                .activeKeys(activeKeys)
                .cooldownKeys(cooldownKeys)
                .inactiveKeys(inactiveKeys)
                .keyHealths(details)
                .redisStatus(redisStatus)
                .redisCachedPairsCount(redisCachedPairsCount)
                .mongoStatus(mongoStatus)
                .mongoSyncStatus(mongoSyncStatus)
                .build();

        return ResponseEntity.ok(health);
    }

    // --- Usage Logs ---

    @GetMapping("/logs")
    public ResponseEntity<List<UsageLog>> getRecentLogs() {
        return ResponseEntity.ok(usageLogRepository.findTop100ByOrderByTimestampDesc());
    }

    // --- Key-Model Mappings ---

    @GetMapping("/mappings")
    public ResponseEntity<List<KeyModelMapping>> getAllMappings() {
        return ResponseEntity.ok(mappingRepository.findAll());
    }

    @PostMapping("/mappings")
    public ResponseEntity<KeyModelMapping> createMapping(@Valid @RequestBody KeyModelMapping mapping) {
        ApiKey key = apiKeyService.getKeyById(mapping.getKeyId())
                .orElseThrow(() -> new IllegalArgumentException("API Key not found with ID: " + mapping.getKeyId()));

        AiModel model = modelService.getAllModels().stream()
                .filter(m -> m.getId().equals(mapping.getModelId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AI Model not found with ID: " + mapping.getModelId()));

        if (!key.getProvider().equalsIgnoreCase(model.getProvider())) {
            throw new IllegalArgumentException("Provider mismatch between API key (" + key.getProvider() + ") and model (" + model.getProvider() + ")");
        }

        // Check if mapping already exists
        KeyModelMapping saved = mappingRepository.findByKeyIdAndModelId(mapping.getKeyId(), mapping.getModelId())
                .orElseGet(() -> mappingRepository.save(mapping));

        // Warm up Redis Cache
        tryWarmupCache();

        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/mappings/{id}")
    public ResponseEntity<Void> deleteMapping(@PathVariable String id) {
        mappingRepository.deleteById(id);
        tryWarmupCache();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/mappings")
    public ResponseEntity<Void> deleteMappingByParams(@RequestParam String keyId, @RequestParam String modelId) {
        mappingRepository.findByKeyIdAndModelId(keyId, modelId)
                .ifPresent(mapping -> mappingRepository.deleteById(mapping.getId()));
        tryWarmupCache();
        return ResponseEntity.noContent().build();
    }

    private void tryWarmupCache() {
        try {
            redisPairCacheService.warmupCache();
        } catch (Exception e) {
            System.err.println("Warning: Redis cache warmup failed: " + e.getMessage());
        }
    }

    // Helper to mask key values
    private ApiKey maskKeyCopy(ApiKey key) {
        ApiKey copy = new ApiKey();
        BeanUtils.copyProperties(key, copy);
        if (copy.getKeyValue() != null && copy.getKeyValue().length() > 8) {
            String val = copy.getKeyValue();
            copy.setKeyValue(val.substring(0, 6) + "..." + val.substring(val.length() - 4));
        }
        return copy;
    }
}
