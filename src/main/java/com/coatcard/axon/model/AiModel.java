package com.coatcard.axon.model;

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

    private String name; // e.g. "gpt-4o", "claude-3-5-sonnet"
    
    private String modelName; // required by spec

    private String provider; // e.g. "openai", "anthropic"

    private String displayName; // e.g. "GPT-4o"

    private boolean active;
    
    private boolean enabled; // required by spec
    
    private int priority; // required by spec (higher = more priority)
}
