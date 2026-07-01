package com.coatcard.axon.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "models")
@CompoundIndexes({
    @CompoundIndex(name = "provider_model_idx", def = "{'provider': 1, 'name': 1}", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModel {
    @Id
    private String id;

    @NotBlank(message = "Model name/ID is required")
    private String name; // e.g. "gpt-4o", "claude-3-5-sonnet"

    @NotBlank(message = "Provider is required")
    private String provider; // e.g. "openai", "anthropic"

    @NotBlank(message = "Display name is required")
    private String displayName; // e.g. "GPT-4o"

    private boolean active;
}
