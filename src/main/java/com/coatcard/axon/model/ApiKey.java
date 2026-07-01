package com.coatcard.axon.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "api_keys")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {
    @Id
    private String id;

    @NotBlank(message = "Friendly name is required")
    private String name;

    @NotBlank(message = "Provider is required")
    @Indexed
    private String provider; // e.g. "openai"

    private String keyValue; // The plain text / legacy API key value
    
    private String apiKey; // The encrypted API key value (required by spec)

    private List<String> models; // Models this key can run, e.g. ["gpt-4o", "gpt-4-turbo"]
    
    private List<String> allowedModels; // Models this key can run (required by spec)

    @Min(value = 0, message = "Limit RPM must be at least 0")
    private int limitRpm; // Requests Per Minute limit

    @Min(value = 0, message = "Limit TPM must be at least 0")
    private int limitTpm; // Tokens Per Minute limit

    @Min(value = 0, message = "Cooldown duration must be at least 0")
    private int cooldownDurationSeconds; // Cooldown duration after failure

    private boolean active;
    
    private ApiKeyStatus status; // ACTIVE, DISABLED (required by spec)

    private Map<String, Object> metadata;

    private Instant createdAt;

    private Instant updatedAt;
}
