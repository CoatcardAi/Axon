package com.coatcard.axon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyHealthDetail {
    private String id;
    private String name;
    private String provider;
    private int currentConcurrency;
    private boolean inCooldown;
    private Long remainingCooldownSeconds;
    private String cooldownReason;
    private int remainingRpm;
    private int remainingTpm;
    private double healthScore;
    private int successCount;
    private int failureCount;
    private Long lastUsed;
}
