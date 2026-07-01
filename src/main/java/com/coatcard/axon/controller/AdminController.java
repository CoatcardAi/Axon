package com.coatcard.axon.controller;

import com.coatcard.axon.dto.KeyHealthDetail;
import com.coatcard.axon.dto.SystemHealthResponse;
import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.UsageLog;
import com.coatcard.axon.repository.UsageLogRepository;
import com.coatcard.axon.service.ApiKeyService;
import com.coatcard.axon.service.CooldownService;
import com.coatcard.axon.service.ModelService;
import com.coatcard.axon.service.RateLimitingService;
import com.coatcard.axon.service.SchedulerService;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final ModelService modelService;
    private final CooldownService cooldownService;
    private final RateLimitingService rateLimitingService;
    private final SchedulerService schedulerService;
    private final UsageLogRepository usageLogRepository;

    public AdminController(ApiKeyService apiKeyService,
                           ModelService modelService,
                           CooldownService cooldownService,
                           RateLimitingService rateLimitingService,
                           SchedulerService schedulerService,
                           UsageLogRepository usageLogRepository) {
        this.apiKeyService = apiKeyService;
        this.modelService = modelService;
        this.cooldownService = cooldownService;
        this.rateLimitingService = rateLimitingService;
        this.schedulerService = schedulerService;
        this.usageLogRepository = usageLogRepository;
    }

    // --- Key Management ---

    @GetMapping("/keys")
    public ResponseEntity<List<ApiKey>> getAllKeys() {
        List<ApiKey> keys = apiKeyService.getAllKeys().stream()
                .map(this::maskKeyCopy)
                .collect(Collectors.toList());
        return ResponseEntity.ok(keys);
    }

    @GetMapping("/keys/{id}")
    public ResponseEntity<ApiKey> getKeyById(@PathVariable String id) {
        return apiKeyService.getKeyById(id)
                .map(this::maskKeyCopy)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/keys")
    public ResponseEntity<ApiKey> createKey(@Valid @RequestBody ApiKey apiKey) {
        ApiKey created = apiKeyService.createKey(apiKey);
        return ResponseEntity.ok(maskKeyCopy(created));
    }

    @PutMapping("/keys/{id}")
    public ResponseEntity<ApiKey> updateKey(@PathVariable String id, @Valid @RequestBody ApiKey apiKeyDetails) {
        try {
            ApiKey updated = apiKeyService.updateKey(id, apiKeyDetails);
            return ResponseEntity.ok(maskKeyCopy(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> deleteKey(@PathVariable String id) {
        apiKeyService.deleteKey(id);
        return ResponseEntity.noContent().build();
    }

    // --- Cooldown Overrides ---

    @PostMapping("/keys/{id}/cooldown")
    public ResponseEntity<String> triggerCooldown(
            @PathVariable String id,
            @RequestParam String reason,
            @RequestParam int durationSeconds) {
        apiKeyService.getKeyById(id)
                .orElseThrow(() -> new IllegalArgumentException("Key not found with id: " + id));

        cooldownService.triggerCooldown(id, "ADMIN_OVERRIDE: " + reason, durationSeconds);
        return ResponseEntity.ok("Key put in cooldown for " + durationSeconds + " seconds.");
    }

    @DeleteMapping("/keys/{id}/cooldown")
    public ResponseEntity<String> clearCooldown(@PathVariable String id) {
        apiKeyService.getKeyById(id)
                .orElseThrow(() -> new IllegalArgumentException("Key not found with id: " + id));

        cooldownService.clearCooldown(id);
        return ResponseEntity.ok("Cooldown cleared for key.");
    }

    // --- Model Management ---

    @GetMapping("/models")
    public ResponseEntity<List<AiModel>> getAllModels() {
        return ResponseEntity.ok(modelService.getAllModels());
    }

    @PostMapping("/models")
    public ResponseEntity<AiModel> createModel(@Valid @RequestBody AiModel model) {
        return ResponseEntity.ok(modelService.createModel(model));
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<AiModel> updateModel(@PathVariable String id, @Valid @RequestBody AiModel modelDetails) {
        try {
            return ResponseEntity.ok(modelService.updateModel(id, modelDetails));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable String id) {
        modelService.deleteModel(id);
        return ResponseEntity.noContent().build();
    }

    // --- System Health and Statistics ---

    @GetMapping("/health")
    public ResponseEntity<SystemHealthResponse> getSystemHealth() {
        List<ApiKey> allKeys = apiKeyService.getAllKeys();

        int totalKeys = allKeys.size();
        int activeKeys = 0;
        int cooldownKeys = 0;
        int inactiveKeys = 0;

        List<KeyHealthDetail> details = new ArrayList<>();

        for (ApiKey key : allKeys) {
            boolean inCooldown = cooldownService.isCooldown(key.getId());
            int concurrency = schedulerService.getConcurrency(key);
            int remainingRpm = rateLimitingService.getRemainingRpm(key);
            int remainingTpm = rateLimitingService.getRemainingTpm(key);

            if (!key.isActive()) {
                inactiveKeys++;
            } else if (inCooldown) {
                cooldownKeys++;
            } else {
                activeKeys++;
            }

            details.add(KeyHealthDetail.builder()
                    .id(key.getId())
                    .name(key.getName())
                    .provider(key.getProvider())
                    .currentConcurrency(concurrency)
                    .inCooldown(inCooldown)
                    .remainingCooldownSeconds(inCooldown ? cooldownService.getCooldownTtl(key.getId()) : 0L)
                    .cooldownReason(inCooldown ? cooldownService.getCooldownReason(key.getId()) : null)
                    .remainingRpm(remainingRpm)
                    .remainingTpm(remainingTpm)
                    .build());
        }

        SystemHealthResponse health = SystemHealthResponse.builder()
                .totalKeys(totalKeys)
                .activeKeys(activeKeys)
                .cooldownKeys(cooldownKeys)
                .inactiveKeys(inactiveKeys)
                .keyHealths(details)
                .build();

        return ResponseEntity.ok(health);
    }

    // --- Usage Logs ---

    @GetMapping("/logs")
    public ResponseEntity<List<UsageLog>> getRecentLogs() {
        return ResponseEntity.ok(usageLogRepository.findTop100ByOrderByTimestampDesc());
    }

    // Helper to mask key values
    private ApiKey maskKeyCopy(ApiKey key) {
        ApiKey copy = new ApiKey();
        BeanUtils.copyProperties(key, copy);
        if (copy.getKeyValue() != null && copy.getKeyValue().length() > 8) {
            String val = copy.getKeyValue();
            copy.setKeyValue(val.substring(0, 6) + "..." + val.substring(val.length() - 4));
        }
        return copy;
    }
}
