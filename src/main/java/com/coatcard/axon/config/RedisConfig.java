package com.coatcard.axon.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

@Configuration
public class RedisConfig {

    private final Environment env;

    public RedisConfig(Environment env) {
        this.env = env;
    }

    public static class SafeRedisServer {
        private final RedisServer redisServer;
        
        public SafeRedisServer(RedisServer redisServer) {
            this.redisServer = redisServer;
        }
        
        public void start() {
            if (redisServer != null) {
                try {
                    redisServer.start();
                    System.out.println("Embedded Redis server started successfully on port 6379.");
                } catch (Exception e) {
                    System.err.println("Failed to start embedded Redis: " + e.getMessage());
                }
            }
        }
        
        public void stop() {
            if (redisServer != null) {
                try {
                    redisServer.stop();
                    System.out.println("Embedded Redis server stopped.");
                } catch (Exception e) {
                    System.err.println("Failed to stop embedded Redis: " + e.getMessage());
                }
            }
        }
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SafeRedisServer embeddedRedisServer() {
        String redisUrl = env.getProperty("spring.data.redis.url");
        if (redisUrl == null || redisUrl.isBlank()) {
            try (java.net.Socket socket = new java.net.Socket("localhost", 6379)) {
                System.out.println("Redis port 6379 is already in use (possibly by Docker or local service). Skipping embedded Redis startup.");
                return new SafeRedisServer(null);
            } catch (IOException e) {
                try {
                    return new SafeRedisServer(new RedisServer(6379));
                } catch (IOException ex) {
                    System.err.println("Failed to construct embedded Redis: " + ex.getMessage());
                    return new SafeRedisServer(null);
                }
            }
        }
        return new SafeRedisServer(null);
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() throws URISyntaxException {
        String redisUrl = env.getProperty("spring.data.redis.url");
        if (redisUrl == null || redisUrl.isBlank()) {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", 6379);
            return new LettuceConnectionFactory(config);
        }

        URI uri = new URI(redisUrl);
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            config.setPassword(RedisPassword.of(uri.getUserInfo().split(":", 2)[1]));
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
