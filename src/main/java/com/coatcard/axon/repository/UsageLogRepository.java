package com.coatcard.axon.repository;

import com.coatcard.axon.model.UsageLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UsageLogRepository extends MongoRepository<UsageLog, String> {
    List<UsageLog> findTop100ByOrderByTimestampDesc();
    List<UsageLog> findByModelOrderByTimestampDesc(String model);
    List<UsageLog> findByKeyIdOrderByTimestampDesc(String keyId);
}
