package com.coatcard.axon.model;

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

    private String name;

    @Indexed
    private String provider; // e.g. "openai"

    private String keyValue; // The actual API key string

    private List<String> models; // Models this key can run, e.g. ["gpt-4o", "gpt-4-turbo"]

    private int limitRpm; // Requests Per Minute limit

    private int limitTpm; // Tokens Per Minute limit

    private int cooldownDurationSeconds; // Cooldown duration after failure

    private boolean active;

    private Map<String, Object> metadata;

    private Instant createdAt;

    private Instant updatedAt;
}
