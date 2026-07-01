package com.coatcard.axon.scheduler;

import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.DailyStatistics;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.redis.RedisPairEntry;
import com.coatcard.axon.repository.AiModelRepository;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.DailyStatisticsRepository;
import com.coatcard.axon.repository.UsageLogRepository;
import com.coatcard.axon.service.RedisPairCacheService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class BackgroundJobs {

    private final RedisPairCacheService cacheService;
    private final ApiKeyRepository apiKeyRepository;
    private final AiModelRepository modelRepository;
    private final UsageLogRepository usageLogRepository;
    private final DailyStatisticsRepository dailyStatisticsRepository;

    public BackgroundJobs(RedisPairCacheService cacheService,
                          ApiKeyRepository apiKeyRepository,
                          AiModelRepository modelRepository,
                          UsageLogRepository usageLogRepository,
                          DailyStatisticsRepository dailyStatisticsRepository) {
        this.cacheService = cacheService;
        this.apiKeyRepository = apiKeyRepository;
        this.modelRepository = modelRepository;
        this.usageLogRepository = usageLogRepository;
        this.dailyStatisticsRepository = dailyStatisticsRepository;
    }

    /**
     * 1. Every 5 minutes: Sync Redis status/counts back to MongoDB ApiKeys.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes in ms
    public void syncRedisToMongo() {
        System.out.println("Background Job: Syncing Redis metrics to MongoDB...");
        Set<String> keys = cacheService.getAllPairKeys();
        if (keys.isEmpty()) return;

        Map<String, List<RedisPairEntry>> keyPairs = new HashMap<>();

        for (String key : keys) {
            String[] parts = key.split(":");
            if (parts.length < 3) continue;
            String provider = parts[0];
            String modelName = parts[1];
            String keyId = parts[2];

            RedisPairEntry entry = cacheService.getPair(provider, modelName, keyId);
            if (entry != null) {
                keyPairs.computeIfAbsent(keyId, k -> new ArrayList<>()).add(entry);
            }
        }

        for (Map.Entry<String, List<RedisPairEntry>> entry : keyPairs.entrySet()) {
            String keyId = entry.getKey();
            List<RedisPairEntry> pairs = entry.getValue();

            Optional<ApiKey> apiKeyOpt = apiKeyRepository.findById(keyId);
            if (apiKeyOpt.isPresent()) {
                ApiKey apiKey = apiKeyOpt.get();
                Map<String, Object> metadata = apiKey.getMetadata();
                if (metadata == null) {
                    metadata = new HashMap<>();
                }

                // Sync success/failure stats per model mapping into the metadata map
                for (RedisPairEntry pair : pairs) {
                    String prefix = "stats:" + pair.getModelId();
                    metadata.put(prefix + ":successCount", pair.getSuccessCount());
                    metadata.put(prefix + ":failureCount", pair.getFailureCount());
                    metadata.put(prefix + ":healthScore", pair.getHealthScore());
                    metadata.put(prefix + ":lastUsed", pair.getLastUsed());
                }

                apiKey.setMetadata(metadata);
                apiKeyRepository.save(apiKey);
            }
        }
        System.out.println("Background Job: Completed syncing metrics for " + keyPairs.size() + " API keys.");
    }

    /**
     * 2. Every 30 minutes: Reload missing active keys/models into Redis.
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes in ms
    public void reloadMissingEntries() {
        System.out.println("Background Job: Reloading missing entries into Redis...");
        cacheService.warmupCache();
    }

    /**
     * 3. Every hour: Remove unhealthy entries from Redis.
     */
    @Scheduled(fixedRate = 3600000) // 1 hour in ms
    public void removeUnhealthyEntries() {
        System.out.println("Background Job: Cleaning up unhealthy Redis entries...");
        Set<String> keys = cacheService.getAllPairKeys();
        int evictedCount = 0;

        for (String key : keys) {
            String[] parts = key.split(":");
            if (parts.length < 3) continue;
            String provider = parts[0];
            String modelName = parts[1];
            String keyId = parts[2];

            RedisPairEntry entry = cacheService.getPair(provider, modelName, keyId);
            if (entry != null) {
                // If it is unhealthy or has extremely low health score, evict it
                if ("UNHEALTHY".equalsIgnoreCase(entry.getCurrentStatus()) || entry.getHealthScore() < 0.2) {
                    cacheService.evictPair(provider, modelName, keyId);
                    evictedCount++;
                }
            }
        }
        System.out.println("Background Job: Evicted " + evictedCount + " unhealthy entries from Redis.");
    }

    /**
     * 4. Every day at midnight: Generate daily usage statistics from MongoDB logs.
     */
    @Scheduled(cron = "0 0 0 * * ?") // Midnight daily
    public void generateDailyStatistics() {
        System.out.println("Background Job: Generating daily usage statistics...");
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Instant start = yesterday.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant end = yesterday.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        List<UsageLog> logs = usageLogRepository.findByTimestampBetween(start, end);
        if (logs.isEmpty()) {
            System.out.println("Background Job: No usage logs found for " + yesterday);
            return;
        }

        // Group logs by KeyId and ModelId
        Map<String, Map<String, List<UsageLog>>> groupedLogs = logs.stream()
                .filter(log -> log.getKeyId() != null && log.getModelId() != null)
                .collect(Collectors.groupingBy(
                        UsageLog::getKeyId,
                        Collectors.groupingBy(UsageLog::getModelId)
                ));

        List<DailyStatistics> dailyStatsList = new ArrayList<>();

        for (Map.Entry<String, Map<String, List<UsageLog>>> keyEntry : groupedLogs.entrySet()) {
            String keyId = keyEntry.getKey();
            for (Map.Entry<String, List<UsageLog>> modelEntry : keyEntry.getValue().entrySet()) {
                String modelId = modelEntry.getKey();
                List<UsageLog> subLogs = modelEntry.getValue();

                long totalRequests = subLogs.size();
                long successCount = subLogs.stream().filter(UsageLog::isSuccess).count();
                long failureCount = totalRequests - successCount;
                
                double averageLatency = subLogs.stream()
                        .mapToLong(UsageLog::getLatency)
                        .average()
                        .orElse(0.0);
                
                long totalTokens = subLogs.stream()
                        .mapToLong(log -> log.getPromptTokens() + log.getCompletionTokens())
                        .sum();

                double successRate = totalRequests > 0 ? (double) successCount / totalRequests : 0.0;

                DailyStatistics stats = DailyStatistics.builder()
                        .date(yesterday)
                        .keyId(keyId)
                        .modelId(modelId)
                        .totalRequests(totalRequests)
                        .successCount(successCount)
                        .failureCount(failureCount)
                        .averageLatency(averageLatency)
                        .totalTokens(totalTokens)
                        .successRate(successRate)
                        .build();

                dailyStatsList.add(stats);
            }
        }

        dailyStatisticsRepository.saveAll(dailyStatsList);
        System.out.println("Background Job: Saved " + dailyStatsList.size() + " daily stats entries for " + yesterday);
    }
}
