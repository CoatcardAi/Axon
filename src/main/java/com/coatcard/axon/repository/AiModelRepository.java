package com.coatcard.axon.repository;

import com.coatcard.axon.model.AiModel;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiModelRepository extends MongoRepository<AiModel, String> {
    Optional<AiModel> findByProviderAndName(String provider, String name);
    List<AiModel> findByProvider(String provider);
}
