package com.coatcard.axon.controller;

import com.coatcard.axon.dto.ScheduledResourceResponse;
import com.coatcard.axon.dto.SchedulerReportRequest;
import com.coatcard.axon.dto.UsageStatsResponse;
import com.coatcard.axon.exception.KeysExhaustedException;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.UsageLogRepository;
import com.coatcard.axon.service.CooldownService;
import com.coatcard.axon.service.RedisPairCacheService;
import com.coatcard.axon.service.RetryEngineService;
import com.coatcard.axon.service.SchedulerService;
import jakarta.validation.Valid;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class ResourceSchedulerController {

    private final SchedulerService schedulerService;
    private final RetryEngineService retryEngineService;
    private final ApiKeyRepository apiKeyRepository;
    private final UsageLogRepository usageLogRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisPairCacheService cacheService;

    public ResourceSchedulerController(SchedulerService schedulerService,
                                       RetryEngineService retryEngineService,
                                       ApiKeyRepository apiKeyRepository,
                                       UsageLogRepository usageLogRepository,
                                       StringRedisTemplate redisTemplate,
                                       RedisPairCacheService cacheService) {
        this.schedulerService = schedulerService;
        this.retryEngineService = retryEngineService;
        this.apiKeyRepository = apiKeyRepository;
        this.usageLogRepository = usageLogRepository;
        this.redisTemplate = redisTemplate;
        this.cacheService = cacheService;
    }

    @GetMapping("/scheduler/resource")
    public ResponseEntity<ScheduledResourceResponse> getBestResource(
            @RequestParam String provider,
            @RequestParam String model,
            @RequestParam(defaultValue = "100") int estimatedTokens) {

        ApiKey apiKey = schedulerService.selectKey(provider.toLowerCase(), model.toLowerCase(), estimatedTokens)
                .orElseThrow(() -> new KeysExhaustedException(
                        "No healthy resources available for provider: " + provider + ", model: " + model
                ));

        ScheduledResourceResponse response = ScheduledResourceResponse.builder()
                .keyId(apiKey.getId())
                .modelId(model.toLowerCase()) // Or look up matching model ID
                .provider(apiKey.getProvider())
                .modelName(model)
                .apiKey(apiKey.getKeyValue()) // Return the decrypted API Key
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/scheduler/report")
    public ResponseEntity<String> reportRequestResult(@Valid @RequestBody SchedulerReportRequest request) {
        retryEngineService.reportResult(
                request.getKeyId(),
                request.getModelId(),
                request.isSuccess(),
                request.getStatusCode(),
                request.getLatency(),
                request.getErrorMessage()
        );
        return ResponseEntity.ok("Result successfully reported.");
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getSchedulerHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        
        // 1. Check Redis Connection
        boolean redisHealthy = false;
        try {
            RedisConnection conn = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection();
            String ping = conn.ping();
            redisHealthy = "PONG".equalsIgnoreCase(ping);
        } catch (Exception e) {
            // Redis error
        }
        
        // 2. Load API Key Counts
        List<ApiKey> allKeys = apiKeyRepository.findAll();
        long totalKeys = allKeys.size();
        long activeKeys = allKeys.stream().filter(ApiKey::isActive).count();
        long disabledKeys = totalKeys - activeKeys;

        health.put("status", redisHealthy ? "UP" : "DOWN");
        health.put("redisConnection", redisHealthy ? "OK" : "FAILED");
        health.put("totalApiKeys", totalKeys);
        health.put("activeApiKeys", activeKeys);
        health.put("disabledApiKeys", disabledKeys);
        
        // Count entries currently cached in Redis
        Set<String> cachedPairs = cacheService.getAllPairKeys();
        health.put("cachedKeyModelPairs", cachedPairs.size());

        return ResponseEntity.ok(health);
    }

    @GetMapping("/stats")
    public ResponseEntity<UsageStatsResponse> getUsageStats() {
        List<UsageLog> logs = usageLogRepository.findAll();
        if (logs.isEmpty()) {
            return ResponseEntity.ok(UsageStatsResponse.builder()
                    .requestsPerModel(Collections.emptyMap())
                    .requestsPerProvider(Collections.emptyMap())
                    .build());
        }

        long totalRequests = logs.size();
        long successCount = logs.stream().filter(UsageLog::isSuccess).count();
        double successRate = totalRequests > 0 ? (double) successCount / totalRequests : 0.0;
        
        double avgLatency = logs.stream()
                .mapToLong(UsageLog::getLatency)
                .average()
                .orElse(0.0);

        Map<String, Long> requestsPerModel = logs.stream()
                .filter(log -> log.getModel() != null)
                .collect(Collectors.groupingBy(UsageLog::getModel, Collectors.counting()));

        Map<String, Long> requestsPerProvider = logs.stream()
                .filter(log -> log.getProvider() != null)
                .collect(Collectors.groupingBy(UsageLog::getProvider, Collectors.counting()));

        UsageStatsResponse stats = UsageStatsResponse.builder()
                .totalRequests(totalRequests)
                .successRate(successRate)
                .averageLatencyMs(avgLatency)
                .requestsPerModel(requestsPerModel)
                .requestsPerProvider(requestsPerProvider)
                .build();

        return ResponseEntity.ok(stats);
    }
}
