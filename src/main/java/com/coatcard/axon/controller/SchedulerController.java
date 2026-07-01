package com.coatcard.axon.controller;

import com.coatcard.axon.dto.*;
import com.coatcard.axon.exception.KeysExhaustedException;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.UsageLogRepository;
import com.coatcard.axon.service.ProxyService;
import com.coatcard.axon.service.RateLimitingService;
import com.coatcard.axon.service.SchedulerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class SchedulerController {

    private final ProxyService proxyService;
    private final SchedulerService schedulerService;
    private final ApiKeyRepository apiKeyRepository;
    private final RateLimitingService rateLimitingService;
    private final UsageLogRepository usageLogRepository;

    public SchedulerController(ProxyService proxyService,
                               SchedulerService schedulerService,
                               ApiKeyRepository apiKeyRepository,
                               RateLimitingService rateLimitingService,
                               UsageLogRepository usageLogRepository) {
        this.proxyService = proxyService;
        this.schedulerService = schedulerService;
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimitingService = rateLimitingService;
        this.usageLogRepository = usageLogRepository;
    }

    // --- Proxy Mode ---

    @PostMapping("/proxy/chat")
    public ResponseEntity<ProxyResponse> chatCompletion(@Valid @RequestBody RouteRequest request) {
        ProxyResponse response = proxyService.executePrompt(
                request.getProvider(),
                request.getModel(),
                request.getPrompt(),
                request.getEstimatedTokens()
        );
        return ResponseEntity.ok(response);
    }

    // --- Resource Scheduling (Checkout/Release) Mode ---

    @PostMapping("/schedule/select")
    public ResponseEntity<CheckoutResponse> checkoutKey(@Valid @RequestBody SelectRequest request) {
        ApiKey apiKey = schedulerService.selectKey(
                request.getProvider(),
                request.getModel(),
                request.getEstimatedTokens()
        ).orElseThrow(() -> new KeysExhaustedException(
                "No keys available for provider: " + request.getProvider() + ", model: " + request.getModel()
        ));

        // Increment concurrency in Redis
        schedulerService.incrementConcurrency(apiKey.getId());

        CheckoutResponse response = CheckoutResponse.builder()
                .keyId(apiKey.getId())
                .keyName(apiKey.getName())
                .provider(apiKey.getProvider())
                .keyValue(apiKey.getKeyValue())
                .limitRpm(apiKey.getLimitRpm())
                .limitTpm(apiKey.getLimitTpm())
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/schedule/release")
    public ResponseEntity<String> releaseKey(@Valid @RequestBody ReleaseRequest request) {
        ApiKey apiKey = apiKeyRepository.findById(request.getKeyId())
                .orElseThrow(() -> new IllegalArgumentException("Key not found with id: " + request.getKeyId()));

        // Decrement concurrency
        schedulerService.decrementConcurrency(apiKey.getId());

        // Increment RPM & TPM usage in Redis
        if (request.getActualTokensUsed() > 0) {
            rateLimitingService.incrementUsage(apiKey.getId(), request.getActualTokensUsed());
        }

        // Save Usage Log
        UsageLog log = UsageLog.builder()
                .keyId(apiKey.getId())
                .keyName(apiKey.getName())
                .provider(apiKey.getProvider())
                .model("CLIENT_ROUTED_MANUAL")
                .promptTokens(0)
                .completionTokens(request.getActualTokensUsed())
                .latencyMs(0)
                .status("SUCCESS")
                .timestamp(Instant.now())
                .build();
        usageLogRepository.save(log);

        return ResponseEntity.ok("Key successfully released and usage logged.");
    }
}
