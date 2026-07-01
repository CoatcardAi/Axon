package com.coatcard.axon.controller;

import com.coatcard.axon.dto.ApiKeyRegisterRequest;
import com.coatcard.axon.dto.MappingRequest;
import com.coatcard.axon.dto.ModelRegisterRequest;
import com.coatcard.axon.model.*;
import com.coatcard.axon.repository.AiModelRepository;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.KeyModelMappingRepository;
import com.coatcard.axon.service.ApiKeyService;
import com.coatcard.axon.service.RedisPairCacheService;
import com.coatcard.axon.utils.EncryptionUtils;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyManagementController {

    private final ApiKeyRepository apiKeyRepository;
    private final AiModelRepository modelRepository;
    private final KeyModelMappingRepository mappingRepository;
    private final RedisPairCacheService cacheService;
    private final EncryptionUtils encryptionUtils;

    public ApiKeyManagementController(ApiKeyRepository apiKeyRepository,
                                      AiModelRepository modelRepository,
                                      KeyModelMappingRepository mappingRepository,
                                      RedisPairCacheService cacheService,
                                      EncryptionUtils encryptionUtils) {
        this.apiKeyRepository = apiKeyRepository;
        this.modelRepository = modelRepository;
        this.mappingRepository = mappingRepository;
        this.cacheService = cacheService;
        this.encryptionUtils = encryptionUtils;
    }

    @PostMapping("/keys")
    public ResponseEntity<ApiKey> registerKey(@Valid @RequestBody ApiKeyRegisterRequest request) {
        // Encrypt the incoming API Key
        String encryptedVal = encryptionUtils.encrypt(request.getApiKey());

        ApiKey apiKey = ApiKey.builder()
                .name(request.getName())
                .provider(request.getProvider().toLowerCase())
                .apiKey(encryptedVal)
                .keyValue(request.getApiKey()) // keep for compatibility
                .limitRpm(request.getLimitRpm())
                .limitTpm(request.getLimitTpm())
                .cooldownDurationSeconds(request.getCooldownDurationSeconds())
                .allowedModels(request.getAllowedModels())
                .models(request.getAllowedModels()) // keep for legacy compatibility
                .active(true)
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ApiKey saved = apiKeyRepository.save(apiKey);
        
        // Warm up Redis Cache with the new key mappings
        cacheService.warmupCache();

        return ResponseEntity.status(HttpStatus.CREATED).body(maskKeyCopy(saved));
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> disableKey(@PathVariable String id) {
        ApiKey apiKey = apiKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API Key not found with ID: " + id));

        apiKey.setActive(false);
        apiKey.setStatus(ApiKeyStatus.DISABLED);
        apiKey.setUpdatedAt(Instant.now());
        apiKeyRepository.save(apiKey);

        // Evict key from Redis
        List<AiModel> models = modelRepository.findAll();
        for (AiModel model : models) {
            cacheService.evictPair(apiKey.getProvider(), model.getName(), apiKey.getId());
        }

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/models")
    public ResponseEntity<AiModel> registerModel(@Valid @RequestBody ModelRegisterRequest request) {
        AiModel model = AiModel.builder()
                .name(request.getModelName().toLowerCase())
                .modelName(request.getModelName().toLowerCase())
                .provider(request.getProvider().toLowerCase())
                .displayName(request.getDisplayName() != null ? request.getDisplayName() : request.getModelName())
                .active(true)
                .enabled(true)
                .priority(request.getPriority())
                .build();

        AiModel saved = modelRepository.save(model);
        
        // Warm up Redis Cache
        cacheService.warmupCache();

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PostMapping("/mapping")
    public ResponseEntity<KeyModelMapping> mapKeyToModel(@Valid @RequestBody MappingRequest request) {
        ApiKey key = apiKeyRepository.findById(request.getKeyId())
                .orElseThrow(() -> new IllegalArgumentException("API Key not found with ID: " + request.getKeyId()));

        AiModel model = modelRepository.findById(request.getModelId())
                .orElseThrow(() -> new IllegalArgumentException("AI Model not found with ID: " + request.getModelId()));

        if (!key.getProvider().equalsIgnoreCase(model.getProvider())) {
            throw new IllegalArgumentException("Provider mismatch between API key (" + key.getProvider() + ") and model (" + model.getProvider() + ")");
        }

        // Check if mapping already exists
        KeyModelMapping mapping = mappingRepository.findByKeyIdAndModelId(request.getKeyId(), request.getModelId())
                .orElseGet(() -> KeyModelMapping.builder()
                        .keyId(request.getKeyId())
                        .modelId(request.getModelId())
                        .build());

        KeyModelMapping saved = mappingRepository.save(mapping);

        // Warm up Redis Cache with the new mapping
        cacheService.warmupCache();

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // Helper to mask key values
    private ApiKey maskKeyCopy(ApiKey key) {
        ApiKey copy = new ApiKey();
        BeanUtils.copyProperties(key, copy);
        if (copy.getKeyValue() != null && copy.getKeyValue().length() > 8) {
            String val = copy.getKeyValue();
            copy.setKeyValue(val.substring(0, 6) + "..." + val.substring(val.length() - 4));
        }
        if (copy.getApiKey() != null && copy.getApiKey().length() > 8) {
            String val = copy.getApiKey();
            copy.setApiKey(val.substring(0, 6) + "..." + val.substring(val.length() - 4));
        }
        return copy;
    }
}
