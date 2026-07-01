package com.coatcard.axon.repository;

import com.coatcard.axon.model.KeyModelMapping;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KeyModelMappingRepository extends MongoRepository<KeyModelMapping, String> {
    List<KeyModelMapping> findByKeyId(String keyId);
    List<KeyModelMapping> findByModelId(String modelId);
    Optional<KeyModelMapping> findByKeyIdAndModelId(String keyId, String modelId);
    void deleteByKeyId(String keyId);
    void deleteByModelId(String modelId);
}
