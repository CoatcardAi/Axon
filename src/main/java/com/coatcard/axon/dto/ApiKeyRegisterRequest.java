package com.coatcard.axon.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ApiKeyRegisterRequest {
    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Provider is required")
    private String provider;

    @NotBlank(message = "ApiKey is required")
    private String apiKey; // Plain text API Key to be encrypted

    private int limitRpm;

    private int limitTpm;

    private int cooldownDurationSeconds;

    private List<String> allowedModels;
}
