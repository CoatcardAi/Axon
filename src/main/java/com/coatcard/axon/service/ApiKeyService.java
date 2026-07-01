package com.coatcard.axon.service;

import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
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
        apiKey.setCreatedAt(Instant.now());
        apiKey.setUpdatedAt(Instant.now());
        return apiKeyRepository.save(apiKey);
    }

    public ApiKey updateKey(String id, ApiKey details) {
        ApiKey existing = apiKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Key not found with id: " + id));

        existing.setName(details.getName());
        existing.setProvider(details.getProvider());
        existing.setKeyValue(details.getKeyValue());
        existing.setModels(details.getModels());
        existing.setLimitRpm(details.getLimitRpm());
        existing.setLimitTpm(details.getLimitTpm());
        existing.setCooldownDurationSeconds(details.getCooldownDurationSeconds());
        existing.setActive(details.isActive());
        existing.setMetadata(details.getMetadata());
        existing.setUpdatedAt(Instant.now());

        return apiKeyRepository.save(existing);
    }

    public void deleteKey(String id) {
        apiKeyRepository.deleteById(id);
    }
}
