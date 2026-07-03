package com.coatcard.axon.redis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedisPairEntry {
    private String keyId;
    private String modelId;
    private String provider;
    private String currentStatus; // ACTIVE, DISABLED, COOLDOWN, UNHEALTHY
    private long lastUsed; // Epoch ms
    private int failureCount;
    private int successCount;
    private long cooldownUntil; // Epoch ms
    private double healthScore;
    private int modelPriority;
    private int providerPriority;
    private int limitRpm;
    private int limitTpm;

    public static RedisPairEntry fromMap(Map<Object, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return RedisPairEntry.builder()
                .keyId((String) map.get("keyId"))
                .modelId((String) map.get("modelId"))
                .provider((String) map.get("provider"))
                .currentStatus((String) map.get("currentStatus"))
                .lastUsed(parseLong(map.get("lastUsed")))
                .failureCount(parseInt(map.get("failureCount")))
                .successCount(parseInt(map.get("successCount")))
                .cooldownUntil(parseLong(map.get("cooldownUntil")))
                .healthScore(parseDouble(map.get("healthScore")))
                .modelPriority(parseInt(map.get("modelPriority")))
                .providerPriority(parseInt(map.get("providerPriority")))
                .limitRpm(parseInt(map.get("limitRpm")))
                .limitTpm(parseInt(map.get("limitTpm")))
                .build();
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("keyId", keyId != null ? keyId : "");
        map.put("modelId", modelId != null ? modelId : "");
        map.put("provider", provider != null ? provider : "");
        map.put("currentStatus", currentStatus != null ? currentStatus : "");
        map.put("lastUsed", String.valueOf(lastUsed));
        map.put("failureCount", String.valueOf(failureCount));
        map.put("successCount", String.valueOf(successCount));
        map.put("cooldownUntil", String.valueOf(cooldownUntil));
        map.put("healthScore", String.valueOf(healthScore));
        map.put("modelPriority", String.valueOf(modelPriority));
        map.put("providerPriority", String.valueOf(providerPriority));
        map.put("limitRpm", String.valueOf(limitRpm));
        map.put("limitTpm", String.valueOf(limitTpm));
        return map;
    }

    private static long parseLong(Object val) {
        if (val == null) return 0L;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int parseInt(Object val) {
        if (val == null) return 0;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(Object val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
