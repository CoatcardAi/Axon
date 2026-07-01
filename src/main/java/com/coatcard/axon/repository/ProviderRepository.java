package com.coatcard.axon.repository;

import com.coatcard.axon.model.Provider;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProviderRepository extends MongoRepository<Provider, String> {
    Optional<Provider> findByName(String name);
}
