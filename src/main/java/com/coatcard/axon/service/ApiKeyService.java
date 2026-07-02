package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.coatcard.axon.model.ApiKeyStatus;
import com.coatcard.axon.utils.EncryptionUtils;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final EncryptionUtils encryptionUtils;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, EncryptionUtils encryptionUtils) {
        this.apiKeyRepository = apiKeyRepository;
        this.encryptionUtils = encryptionUtils;
    }

    public List<ApiKey> getAllKeys() {
        return apiKeyRepository.findAll();
    }

    public List<ApiKey> getKeysByProvider(String provider) {
        return apiKeyRepository.findByProvider(provider);
    }

    public Optional<ApiKey> getKeyById(String id) {
        return apiKeyRepository.findById(id);
    }

    public ApiKey createKey(ApiKey apiKey) {
        if (apiKey.getId() != null && apiKey.getId().isBlank()) {
            apiKey.setId(null);
        }
        syncFields(apiKey);
        apiKey.setCreatedAt(Instant.now());
        apiKey.setUpdatedAt(Instant.now());
        return apiKeyRepository.save(apiKey);
    }

    public ApiKey updateKey(String id, ApiKey details) {
        ApiKey existing = apiKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Key not found with id: " + id));

        existing.setName(details.getName());
        existing.setProvider(details.getProvider());
        
        if (details.getKeyValue() != null && !details.getKeyValue().isBlank() && !details.getKeyValue().contains("...")) {
            existing.setKeyValue(details.getKeyValue());
        }
        if (details.getApiKey() != null && !details.getApiKey().isBlank() && !details.getApiKey().contains("...")) {
            existing.setApiKey(details.getApiKey());
        }

        if (details.getModels() != null) {
            existing.setModels(details.getModels());
        } else if (details.getAllowedModels() != null) {
            existing.setAllowedModels(details.getAllowedModels());
        }

                existing.setLimitRpm(details.getLimitRpm());
        existing.setLimitTpm(details.getLimitTpm());
        existing.setCooldownDurationSeconds(details.getCooldownDurationSeconds());
        
        // Synchronize active and status based on which one changed
        boolean activeChanged = details.isActive() != existing.isActive();
        boolean statusChanged = details.getStatus() != null && details.getStatus() != existing.getStatus();
        
        if (activeChanged && !statusChanged) {
            existing.setActive(details.isActive());
            existing.setStatus(details.isActive() ? ApiKeyStatus.ACTIVE : ApiKeyStatus.DISABLED);
        } else if (statusChanged && !activeChanged) {
            existing.setStatus(details.getStatus());
            existing.setActive(details.getStatus() == ApiKeyStatus.ACTIVE);
        } else {
            if (details.getStatus() != null) {
                existing.setStatus(details.getStatus());
                existing.setActive(details.getStatus() == ApiKeyStatus.ACTIVE);
            } else {
                existing.setActive(details.isActive());
                existing.setStatus(details.isActive() ? ApiKeyStatus.ACTIVE : ApiKeyStatus.DISABLED);
            }
        }
        
        existing.setMetadata(details.getMetadata());
        existing.setUpdatedAt(Instant.now());

        syncFields(existing);
        return apiKeyRepository.save(existing);
    }

    public void deleteKey(String id) {
        apiKeyRepository.deleteById(id);
    }

    private void syncFields(ApiKey key) {
        if (key.getKeyValue() != null && !key.getKeyValue().isBlank() && !key.getKeyValue().contains("...")) {
            key.setApiKey(encryptionUtils.encrypt(key.getKeyValue()));
        } else if (key.getApiKey() != null && !key.getApiKey().isBlank() && !key.getApiKey().contains("...")) {
            key.setKeyValue(encryptionUtils.decrypt(key.getApiKey()));
        }

        if (key.getModels() != null) {
            key.setAllowedModels(new java.util.ArrayList<>(key.getModels()));
        } else if (key.getAllowedModels() != null) {
            key.setModels(new java.util.ArrayList<>(key.getAllowedModels()));
        }

        if (key.getStatus() != null) {
            key.setActive(key.getStatus() == ApiKeyStatus.ACTIVE);
        } else {
            key.setStatus(key.isActive() ? ApiKeyStatus.ACTIVE : ApiKeyStatus.DISABLED);
        }
    }
}
