package com.coatcard.axon.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SchedulerReportRequest {
    @NotBlank(message = "Key ID is required")
    private String keyId;

    @NotBlank(message = "Model ID is required")
    private String modelId;

    private boolean success;

    private int statusCode;

    private long latency;

    private String errorMessage;
}
