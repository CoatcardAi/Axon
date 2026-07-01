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
        Boolean hasKey = stringRedisTemplate.hasKey(getCooldownKey(keyId));
        return hasKey != null && hasKey;
    }

    public void triggerCooldown(String keyId, String reason, int durationSeconds) {
        String key = getCooldownKey(keyId);
        stringRedisTemplate.opsForValue().set(key, reason, durationSeconds, TimeUnit.SECONDS);
    }

    public void clearCooldown(String keyId) {
        stringRedisTemplate.delete(getCooldownKey(keyId));
    }

    public Long getCooldownTtl(String keyId) {
        return stringRedisTemplate.getExpire(getCooldownKey(keyId), TimeUnit.SECONDS);
    }

    public String getCooldownReason(String keyId) {
        return stringRedisTemplate.opsForValue().get(getCooldownKey(keyId));
    }

    private String getCooldownKey(String keyId) {
        return "cooldown:" + keyId;
    }
}
