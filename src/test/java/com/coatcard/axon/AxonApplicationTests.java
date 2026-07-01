package com.coatcard.axon;

import com.coatcard.axon.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
    "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration," +
    "org.springframework.boot.autoconfigure.data.mongo.MongoAutoConfiguration," +
    "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
    "spring.data.redis.repositories.enabled=false",
    "spring.data.redis.url=redis://localhost:6379",
    "spring.data.mongodb.uri=mongodb://localhost:27017/test"
})
class AxonApplicationTests {

    static {
        AxonApplication.loadDotenv();
    }

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
    private KeyModelMappingRepository keyModelMappingRepository;

    @MockBean
    private DailyStatisticsRepository dailyStatisticsRepository;

    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockBean
    private org.springframework.data.redis.connection.ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
    }

}
