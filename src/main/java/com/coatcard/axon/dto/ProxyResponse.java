package com.coatcard.axon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProxyResponse {
    private String selectedKeyId;
    private String selectedKeyName;
    private String provider;
    private String model;
    private String responseText;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private int attempts;
}
