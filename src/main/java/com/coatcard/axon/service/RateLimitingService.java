package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RateLimitingService {

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitingService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean isRateLimited(ApiKey apiKey, int estimatedTokens) {
        long currentMinute = System.currentTimeMillis() / 60000;
        String rpmKey = getRpmKey(apiKey.getId(), currentMinute);
        String tpmKey = getTpmKey(apiKey.getId(), currentMinute);

        String rpmVal = stringRedisTemplate.opsForValue().get(rpmKey);
        String tpmVal = stringRedisTemplate.opsForValue().get(tpmKey);

        int currentRpm = rpmVal != null ? Integer.parseInt(rpmVal) : 0;
        int currentTpm = tpmVal != null ? Integer.parseInt(tpmVal) : 0;

        if (apiKey.getLimitRpm() > 0 && currentRpm >= apiKey.getLimitRpm()) {
            return true;
        }

        if (apiKey.getLimitTpm() > 0 && (currentTpm + estimatedTokens) > apiKey.getLimitTpm()) {
            return true;
        }

        return false;
    }

    public void incrementUsage(String keyId, int tokens) {
        long currentMinute = System.currentTimeMillis() / 60000;
        String rpmKey = getRpmKey(keyId, currentMinute);
        String tpmKey = getTpmKey(keyId, currentMinute);

        Long rpm = stringRedisTemplate.opsForValue().increment(rpmKey, 1);
        if (rpm != null && rpm == 1) {
            stringRedisTemplate.expire(rpmKey, 120, TimeUnit.SECONDS);
        }

        Long tpm = stringRedisTemplate.opsForValue().increment(tpmKey, tokens);
        if (tpm != null && tpm == tokens) {
            stringRedisTemplate.expire(tpmKey, 120, TimeUnit.SECONDS);
        }
    }

    public int getRemainingRpm(ApiKey apiKey) {
        long currentMinute = System.currentTimeMillis() / 60000;
        String rpmKey = getRpmKey(apiKey.getId(), currentMinute);
        String rpmVal = stringRedisTemplate.opsForValue().get(rpmKey);
        int currentRpm = rpmVal != null ? Integer.parseInt(rpmVal) : 0;
        return Math.max(0, apiKey.getLimitRpm() - currentRpm);
    }

    public int getRemainingTpm(ApiKey apiKey) {
        long currentMinute = System.currentTimeMillis() / 60000;
        String tpmKey = getTpmKey(apiKey.getId(), currentMinute);
        String tpmVal = stringRedisTemplate.opsForValue().get(tpmKey);
        int currentTpm = tpmVal != null ? Integer.parseInt(tpmVal) : 0;
        return Math.max(0, apiKey.getLimitTpm() - currentTpm);
    }

    private String getRpmKey(String keyId, long minute) {
        return "rate:rpm:" + keyId + ":" + minute;
    }

    private String getTpmKey(String keyId, long minute) {
        return "rate:tpm:" + keyId + ":" + minute;
    }
}
