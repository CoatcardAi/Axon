package com.coatcard.axon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageStatsResponse {
    private long totalRequests;
    private double successRate;
    private double averageLatencyMs;
    private Map<String, Long> requestsPerModel;
    private Map<String, Long> requestsPerProvider;
}
