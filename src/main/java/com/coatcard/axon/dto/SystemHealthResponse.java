package com.coatcard.axon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemHealthResponse {
    private int totalKeys;
    private int activeKeys;
    private int cooldownKeys;
    private int inactiveKeys;
    private List<KeyHealthDetail> keyHealths;
}
