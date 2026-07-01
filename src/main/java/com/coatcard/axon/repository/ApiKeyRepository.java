package com.coatcard.axon.repository;

import com.coatcard.axon.model.ApiKey;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApiKeyRepository extends MongoRepository<ApiKey, String> {
    List<ApiKey> findByProviderAndModelsContainingAndActiveTrue(String provider, String model);
    List<ApiKey> findByActiveTrue();
    List<ApiKey> findByProvider(String provider);
}
