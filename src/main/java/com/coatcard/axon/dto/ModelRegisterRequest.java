package com.coatcard.axon.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ModelRegisterRequest {
    @NotBlank(message = "ModelName is required")
    private String modelName;

    @NotBlank(message = "Provider is required")
    private String provider;

    private String displayName;

    private int priority;
}
