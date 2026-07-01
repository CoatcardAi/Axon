package com.coatcard.axon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "key_model_mapping")
@CompoundIndexes({
    @CompoundIndex(name = "key_model_idx", def = "{'keyId': 1, 'modelId': 1}", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyModelMapping {
    @Id
    private String id;
    
    private String keyId;
    
    private String modelId;
}
