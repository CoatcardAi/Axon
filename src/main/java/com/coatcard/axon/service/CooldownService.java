package com.coatcard.axon.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CooldownService {

    private final StringRedisTemplate stringRedisTemplate;

    public CooldownService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean isCooldown(String keyId) {
        try {
            Boolean hasKey = stringRedisTemplate.hasKey(getCooldownKey(keyId));
            return hasKey != null && hasKey;
        } catch (Exception e) {
            System.err.println("Redis cooldown check failed: " + e.getMessage());
            return false;
        }
    }

    public void triggerCooldown(String keyId, String reason, int durationSeconds) {
        try {
            String key = getCooldownKey(keyId);
            stringRedisTemplate.opsForValue().set(key, reason, durationSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Redis triggerCooldown failed: " + e.getMessage());
        }
    }

    public void clearCooldown(String keyId) {
        try {
            stringRedisTemplate.delete(getCooldownKey(keyId));
        } catch (Exception e) {
            System.err.println("Redis clearCooldown failed: " + e.getMessage());
        }
    }

    public Long getCooldownTtl(String keyId) {
        try {
            return stringRedisTemplate.getExpire(getCooldownKey(keyId), TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Redis getCooldownTtl failed: " + e.getMessage());
            return 0L;
        }
    }

    public String getCooldownReason(String keyId) {
        try {
            return stringRedisTemplate.opsForValue().get(getCooldownKey(keyId));
        } catch (Exception e) {
            System.err.println("Redis getCooldownReason failed: " + e.getMessage());
            return null;
        }
    }

    private String getCooldownKey(String keyId) {
        return "cooldown:" + keyId;
    }
}
