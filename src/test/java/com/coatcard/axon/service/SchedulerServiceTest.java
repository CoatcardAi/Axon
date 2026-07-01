package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.redis.RedisPairEntry;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.strategy.LeastRecentlyUsedStrategy;
import com.coatcard.axon.strategy.RoundRobinStrategy;
import com.coatcard.axon.strategy.SchedulingStrategy;
import com.coatcard.axon.utils.EncryptionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SchedulerServiceTest {

    private ApiKeyRepository apiKeyRepository;
    private RateLimitingService rateLimitingService;
    private CooldownService cooldownService;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisPairCacheService redisPairCacheService;
    private EncryptionUtils encryptionUtils;

    private SchedulerService schedulerService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        apiKeyRepository = Mockito.mock(ApiKeyRepository.class);
        rateLimitingService = Mockito.mock(RateLimitingService.class);
        cooldownService = Mockito.mock(CooldownService.class);
        stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        redisPairCacheService = Mockito.mock(RedisPairCacheService.class);
        encryptionUtils = Mockito.mock(EncryptionUtils.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(encryptionUtils.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Use actual strategy implementations to verify logic
        List<SchedulingStrategy> strategies = List.of(
                new LeastRecentlyUsedStrategy(),
                new RoundRobinStrategy()
        );

        schedulerService = new SchedulerService(
                apiKeyRepository,
                rateLimitingService,
                cooldownService,
                stringRedisTemplate,
                redisPairCacheService,
                encryptionUtils,
                strategies
        );

        // Set default strategy to LRU for tests
        ReflectionTestUtils.setField(schedulerService, "defaultStrategyName", "LRU");
    }

    @Test
    void testSelectKey_LeastRecentlyUsedPickedFirst() {
        // key1 was used recently (higher timestamp), key2 was used in past (lower timestamp)
        RedisPairEntry entry1 = RedisPairEntry.builder()
                .keyId("key1")
                .modelId("gpt-4o")
                .provider("openai")
                .currentStatus("ACTIVE")
                .lastUsed(2000L)
                .healthScore(1.0)
                .build();
        RedisPairEntry entry2 = RedisPairEntry.builder()
                .keyId("key2")
                .modelId("gpt-4o")
                .provider("openai")
                .currentStatus("ACTIVE")
                .lastUsed(1000L)
                .healthScore(1.0)
                .build();

        ApiKey key1 = ApiKey.builder().id("key1").name("Key 1").provider("openai").active(true).build();
        ApiKey key2 = ApiKey.builder().id("key2").name("Key 2").provider("openai").active(true).build();

        when(redisPairCacheService.getCandidates("openai", "gpt-4o"))
                .thenReturn(List.of(entry1, entry2));

        when(apiKeyRepository.findById("key1")).thenReturn(Optional.of(key1));
        when(apiKeyRepository.findById("key2")).thenReturn(Optional.of(key2));

        when(cooldownService.isCooldown(any())).thenReturn(false);
        when(rateLimitingService.isRateLimited(any(), any(Integer.class))).thenReturn(false);

        Optional<ApiKey> selected = schedulerService.selectKey("openai", "gpt-4o", 100);

        assertTrue(selected.isPresent());
        assertEquals("key2", selected.get().getId()); // key2 has oldest lastUsed (1000 < 2000)
    }

    @Test
    void testSelectKey_FiltersOutCooldownEntries() {
        long now = System.currentTimeMillis();
        
        // key1 is in cooldown until future
        RedisPairEntry entry1 = RedisPairEntry.builder()
                .keyId("key1")
                .modelId("gpt-4o")
                .provider("openai")
                .currentStatus("ACTIVE")
                .lastUsed(1000L)
                .cooldownUntil(now + 10000L)
                .healthScore(1.0)
                .build();
        // key2 is active
        RedisPairEntry entry2 = RedisPairEntry.builder()
                .keyId("key2")
                .modelId("gpt-4o")
                .provider("openai")
                .currentStatus("ACTIVE")
                .lastUsed(1000L)
                .cooldownUntil(0L)
                .healthScore(1.0)
                .build();

        ApiKey key1 = ApiKey.builder().id("key1").name("Key 1").provider("openai").active(true).build();
        ApiKey key2 = ApiKey.builder().id("key2").name("Key 2").provider("openai").active(true).build();

        when(redisPairCacheService.getCandidates("openai", "gpt-4o"))
                .thenReturn(List.of(entry1, entry2));

        when(apiKeyRepository.findById("key2")).thenReturn(Optional.of(key2));
        when(rateLimitingService.isRateLimited(any(), any(Integer.class))).thenReturn(false);

        Optional<ApiKey> selected = schedulerService.selectKey("openai", "gpt-4o", 100);

        assertTrue(selected.isPresent());
        assertEquals("key2", selected.get().getId()); // key1 in cooldown, key2 selected
    }

    @Test
    void testSelectKey_ReturnsEmptyWhenAllKeysInCooldown() {
        long now = System.currentTimeMillis();
        RedisPairEntry entry1 = RedisPairEntry.builder()
                .keyId("key1")
                .modelId("gpt-4o")
                .provider("openai")
                .currentStatus("ACTIVE")
                .cooldownUntil(now + 10000L)
                .healthScore(1.0)
                .build();

        when(redisPairCacheService.getCandidates("openai", "gpt-4o"))
                .thenReturn(List.of(entry1));

        Optional<ApiKey> selected = schedulerService.selectKey("openai", "gpt-4o", 100);

        assertFalse(selected.isPresent());
    }
}
