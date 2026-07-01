package com.coatcard.axon.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReleaseRequest {
    @NotBlank(message = "Key ID is required")
    private String keyId;

    private int actualTokensUsed;
}
