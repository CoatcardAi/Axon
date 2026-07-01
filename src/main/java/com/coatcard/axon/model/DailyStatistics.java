package com.coatcard.axon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Document(collection = "daily_statistics")
@CompoundIndexes({
    @CompoundIndex(name = "date_key_model_idx", def = "{'date': 1, 'keyId': 1, 'modelId': 1}", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStatistics {
    @Id
    private String id;
    
    private LocalDate date;
    
    private String keyId;
    
    private String modelId;
    
    private long totalRequests;
    
    private long successCount;
    
    private long failureCount;
    
    private double averageLatency;
    
    private long totalTokens;
    
    private double successRate;
}
