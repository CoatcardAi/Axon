package com.coatcard.axon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledResourceResponse {
    private String keyId;
    private String modelId;
    private String provider;
    private String modelName;
    private String apiKey; // Decrypted actual API key string
}
