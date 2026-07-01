package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.repository.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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

    private SchedulerService schedulerService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        apiKeyRepository = Mockito.mock(ApiKeyRepository.class);
        rateLimitingService = Mockito.mock(RateLimitingService.class);
        cooldownService = Mockito.mock(CooldownService.class);
        stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        schedulerService = new SchedulerService(
                apiKeyRepository,
                rateLimitingService,
                cooldownService,
                stringRedisTemplate
        );
    }

    @Test
    void testSelectKey_LeastConcurrencyPickedFirst() {
        ApiKey key1 = ApiKey.builder().id("key1").name("Key 1").provider("openai").limitRpm(60).limitTpm(100000).active(true).build();
        ApiKey key2 = ApiKey.builder().id("key2").name("Key 2").provider("openai").limitRpm(60).limitTpm(100000).active(true).build();

        when(apiKeyRepository.findByProviderAndModelsContainingAndActiveTrue("openai", "gpt-4o"))
                .thenReturn(List.of(key1, key2));

        when(cooldownService.isCooldown(any())).thenReturn(false);
        when(rateLimitingService.isRateLimited(any(), any(Integer.class))).thenReturn(false);

        // Redis concurrency: key1 has 5 active connections, key2 has 2 active connections
        when(valueOperations.get("concurrency:key1")).thenReturn("5");
        when(valueOperations.get("concurrency:key2")).thenReturn("2");

        when(rateLimitingService.getRemainingRpm(any())).thenReturn(50);
        when(rateLimitingService.getRemainingTpm(any())).thenReturn(80000);

        Optional<ApiKey> selected = schedulerService.selectKey("openai", "gpt-4o", 100);

        assertTrue(selected.isPresent());
        assertEquals("key2", selected.get().getId()); // Least concurrency (2 < 5) should be selected
    }

    @Test
    void testSelectKey_HighestCapacityPickedWhenConcurrencyTied() {
        ApiKey key1 = ApiKey.builder().id("key1").name("Key 1").provider("openai").limitRpm(100).limitTpm(100000).active(true).build();
        ApiKey key2 = ApiKey.builder().id("key2").name("Key 2").provider("openai").limitRpm(100).limitTpm(100000).active(true).build();

        when(apiKeyRepository.findByProviderAndModelsContainingAndActiveTrue("openai", "gpt-4o"))
                .thenReturn(List.of(key1, key2));

        when(cooldownService.isCooldown(any())).thenReturn(false);
        when(rateLimitingService.isRateLimited(any(), any(Integer.class))).thenReturn(false);

        // Concurrency is equal
        when(valueOperations.get("concurrency:key1")).thenReturn("1");
        when(valueOperations.get("concurrency:key2")).thenReturn("1");

        // key1 has 90% remaining capacity, key2 has 50% remaining capacity
        when(rateLimitingService.getRemainingRpm(key1)).thenReturn(90);
        when(rateLimitingService.getRemainingTpm(key1)).thenReturn(90000);

        when(rateLimitingService.getRemainingRpm(key2)).thenReturn(50);
        when(rateLimitingService.getRemainingTpm(key2)).thenReturn(50000);

        Optional<ApiKey> selected = schedulerService.selectKey("openai", "gpt-4o", 100);

        assertTrue(selected.isPresent());
        assertEquals("key1", selected.get().getId()); // key1 has more remaining capacity (90% > 50%)
    }

    @Test
    void testSelectKey_ReturnsEmptyWhenAllKeysInCooldown() {
        ApiKey key1 = ApiKey.builder().id("key1").name("Key 1").provider("openai").active(true).build();

        when(apiKeyRepository.findByProviderAndModelsContainingAndActiveTrue("openai", "gpt-4o"))
                .thenReturn(List.of(key1));

        when(cooldownService.isCooldown("key1")).thenReturn(true);

        Optional<ApiKey> selected = schedulerService.selectKey("openai", "gpt-4o", 100);

        assertFalse(selected.isPresent());
    }
}
