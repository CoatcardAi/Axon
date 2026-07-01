package com.coatcard.axon.strategy;

import com.coatcard.axon.redis.RedisPairEntry;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class PriorityBasedStrategy implements SchedulingStrategy {

    @Override
    public String getName() {
        return "PRIORITY";
    }

    @Override
    public Optional<RedisPairEntry> select(List<RedisPairEntry> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        // Sort descending by model priority, then descending by provider priority,
        // then descending by healthScore, then ascending by lastUsed.
        return candidates.stream()
                .max(Comparator
                        .comparingInt(RedisPairEntry::getModelPriority)
                        .thenComparingInt(RedisPairEntry::getProviderPriority)
                        .thenComparingDouble(RedisPairEntry::getHealthScore)
                        .thenComparingLong(entry -> -entry.getLastUsed()) // older lastUsed means higher priority
                );
    }
}
