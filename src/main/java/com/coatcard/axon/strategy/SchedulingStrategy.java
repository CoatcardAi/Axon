package com.coatcard.axon.strategy;

import com.coatcard.axon.redis.RedisPairEntry;

import java.util.List;
import java.util.Optional;

public interface SchedulingStrategy {
    /**
     * Get the strategy name.
     */
    String getName();

    /**
     * Select the best candidate pair from the list of candidates.
     */
    Optional<RedisPairEntry> select(List<RedisPairEntry> candidates);
}
