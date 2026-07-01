package com.coatcard.axon.repository;

import com.coatcard.axon.model.DailyStatistics;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyStatisticsRepository extends MongoRepository<DailyStatistics, String> {
    List<DailyStatistics> findByDate(LocalDate date);
    List<DailyStatistics> findByKeyId(String keyId);
    List<DailyStatistics> findByModelId(String modelId);
}
