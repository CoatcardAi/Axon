package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.redis.RedisPairEntry;
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
        return isRateLimited(apiKey.getId(), apiKey.getLimitRpm(), apiKey.getLimitTpm(), estimatedTokens);
    }

    public boolean isRateLimitedForEntry(RedisPairEntry entry, int estimatedTokens) {
        return isRateLimited(entry.getKeyId(), entry.getLimitRpm(), entry.getLimitTpm(), estimatedTokens);
    }

    public boolean isRateLimited(String keyId, int limitRpm, int limitTpm, int estimatedTokens) {
        try {
            long currentMinute = System.currentTimeMillis() / 60000;
            String rpmKey = getRpmKey(keyId, currentMinute);
            String tpmKey = getTpmKey(keyId, currentMinute);

            String rpmVal = stringRedisTemplate.opsForValue().get(rpmKey);
            String tpmVal = stringRedisTemplate.opsForValue().get(tpmKey);

            int currentRpm = rpmVal != null ? Integer.parseInt(rpmVal) : 0;
            int currentTpm = tpmVal != null ? Integer.parseInt(tpmVal) : 0;

            if (limitRpm > 0 && currentRpm >= limitRpm) {
                return true;
            }

            if (limitTpm > 0 && (currentTpm + estimatedTokens) > limitTpm) {
                return true;
            }

            return false;
        } catch (Exception e) {
            System.err.println("Redis rate limit check failed: " + e.getMessage());
            return false;
        }
    }

    public void incrementUsage(String keyId, int tokens) {
        try {
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
        } catch (Exception e) {
            System.err.println("Redis incrementUsage failed: " + e.getMessage());
        }
    }

    public int getRemainingRpm(ApiKey apiKey) {
        try {
            long currentMinute = System.currentTimeMillis() / 60000;
            String rpmKey = getRpmKey(apiKey.getId(), currentMinute);
            String rpmVal = stringRedisTemplate.opsForValue().get(rpmKey);
            int currentRpm = rpmVal != null ? Integer.parseInt(rpmVal) : 0;
            return Math.max(0, apiKey.getLimitRpm() - currentRpm);
        } catch (Exception e) {
            System.err.println("Redis getRemainingRpm failed: " + e.getMessage());
            return apiKey.getLimitRpm();
        }
    }

    public int getRemainingTpm(ApiKey apiKey) {
        try {
            long currentMinute = System.currentTimeMillis() / 60000;
            String tpmKey = getTpmKey(apiKey.getId(), currentMinute);
            String tpmVal = stringRedisTemplate.opsForValue().get(tpmKey);
            int currentTpm = tpmVal != null ? Integer.parseInt(tpmVal) : 0;
            return Math.max(0, apiKey.getLimitTpm() - currentTpm);
        } catch (Exception e) {
            System.err.println("Redis getRemainingTpm failed: " + e.getMessage());
            return apiKey.getLimitTpm();
        }
    }

    public void adjustTpmUsage(String keyId, int diff) {
        if (diff == 0) return;
        try {
            long currentMinute = System.currentTimeMillis() / 60000;
            String tpmKey = getTpmKey(keyId, currentMinute);
            Long tpm = stringRedisTemplate.opsForValue().increment(tpmKey, diff);
            if (tpm != null && tpm <= diff) {
                stringRedisTemplate.expire(tpmKey, 120, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            System.err.println("Redis adjustTpmUsage failed: " + e.getMessage());
        }
    }

    public void refundUsage(String keyId, int estimatedTokens) {
        try {
            long currentMinute = System.currentTimeMillis() / 60000;
            String rpmKey = getRpmKey(keyId, currentMinute);
            String tpmKey = getTpmKey(keyId, currentMinute);

            Long rpm = stringRedisTemplate.opsForValue().increment(rpmKey, -1);
            if (rpm != null && rpm < 0) {
                stringRedisTemplate.opsForValue().set(rpmKey, "0");
            }
            Long tpm = stringRedisTemplate.opsForValue().increment(tpmKey, -estimatedTokens);
            if (tpm != null && tpm < 0) {
                stringRedisTemplate.opsForValue().set(tpmKey, "0");
            }
        } catch (Exception e) {
            System.err.println("Redis refundUsage failed: " + e.getMessage());
        }
    }

    private String getRpmKey(String keyId, long minute) {
        return "rate:rpm:" + keyId + ":" + minute;
    }

    private String getTpmKey(String keyId, long minute) {
        return "rate:tpm:" + keyId + ":" + minute;
    }
}
