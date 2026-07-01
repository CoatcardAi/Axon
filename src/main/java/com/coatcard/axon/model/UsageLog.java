package com.coatcard.axon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "usage_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageLog {
    @Id
    private String id;

    @Indexed
    private String keyId;

    private String keyName;

    @Indexed
    private String provider;

    @Indexed
    private String model;
    
    private String modelId; // required by spec

    private int promptTokens;

    private int completionTokens;

    private long latencyMs;
    
    private long latency; // required by spec

    private String status; // "SUCCESS", "RATE_LIMIT_ERROR", "PROVIDER_ERROR", "CLIENT_ERROR"
    
    private boolean success; // required by spec
    
    private String errorCode; // required by spec

    private String errorMessage;

    private String prompt;

    private String responseText;

    @Indexed
    private Instant timestamp;
    
    private Instant requestTime; // required by spec
}
