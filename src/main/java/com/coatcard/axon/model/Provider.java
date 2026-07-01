package com.coatcard.axon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "providers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Provider {
    @Id
    private String id;

    @Indexed(unique = true)
    private String name; // e.g. "openai", "anthropic", "gemini"

    private String displayName; // e.g. "OpenAI", "Anthropic", "Google Gemini"

    private boolean active;
}
