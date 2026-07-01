package com.coatcard.axon;

import com.coatcard.axon.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class AxonApplicationTests {

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProviderRepository providerRepository;

    @MockBean
    private AiModelRepository aiModelRepository;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private UsageLogRepository usageLogRepository;

    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
    }

}
