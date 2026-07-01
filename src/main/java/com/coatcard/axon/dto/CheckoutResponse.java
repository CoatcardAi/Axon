package com.coatcard.axon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutResponse {
    private String keyId;
    private String keyName;
    private String provider;
    private String keyValue;
    private int limitRpm;
    private int limitTpm;
}
