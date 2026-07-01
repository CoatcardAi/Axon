package com.coatcard.axon.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MappingRequest {
    @NotBlank(message = "Key ID is required")
    private String keyId;

    @NotBlank(message = "Model ID is required")
    private String modelId;
}
