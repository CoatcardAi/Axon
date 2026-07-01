package com.coatcard.axon.strategy;

import com.coatcard.axon.redis.RedisPairEntry;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class LeastRecentlyUsedStrategy implements SchedulingStrategy {

    @Override
    public String getName() {
        return "LRU";
    }

    @Override
    public Optional<RedisPairEntry> select(List<RedisPairEntry> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        // Pick the one with the smallest lastUsed value (oldest/least recently used)
        return candidates.stream()
                .min(Comparator.comparingLong(RedisPairEntry::getLastUsed));
    }
}
