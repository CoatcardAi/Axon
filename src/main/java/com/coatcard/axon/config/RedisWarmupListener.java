package com.coatcard.axon.config;

import com.coatcard.axon.service.RedisPairCacheService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class RedisWarmupListener implements ApplicationListener<ApplicationReadyEvent> {

    private final RedisPairCacheService cacheService;

    public RedisWarmupListener(RedisPairCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        System.out.println("Application ready. Warming up Redis API Key cache...");
        try {
            cacheService.warmupCache();
        } catch (Exception e) {
            System.err.println("Failed to warm up Redis cache on startup: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
