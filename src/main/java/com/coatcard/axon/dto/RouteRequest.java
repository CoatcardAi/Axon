package com.coatcard.axon.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteRequest {
    @NotBlank(message = "Provider is required")
    private String provider;

    @NotBlank(message = "Model is required")
    private String model;

    @NotBlank(message = "Prompt is required")
    private String prompt;

    @Min(value = 1, message = "Estimated tokens must be at least 1")
    private int estimatedTokens = 100;
}
