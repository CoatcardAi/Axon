package com.coatcard.axon.strategy;

import com.coatcard.axon.redis.RedisPairEntry;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

@Component
public class WeightedRandomStrategy implements SchedulingStrategy {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String getName() {
        return "WEIGHTED_RANDOM";
    }

    @Override
    public Optional<RedisPairEntry> select(List<RedisPairEntry> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        double totalWeight = candidates.stream()
                .mapToDouble(RedisPairEntry::getHealthScore)
                .sum();

        if (totalWeight <= 0.0) {
            // Fallback to uniform random if all weights are zero
            int index = random.nextInt(candidates.size());
            return Optional.of(candidates.get(index));
        }

        double randomValue = random.nextDouble() * totalWeight;
        double currentSum = 0.0;

        for (RedisPairEntry candidate : candidates) {
            currentSum += candidate.getHealthScore();
            if (randomValue <= currentSum) {
                return Optional.of(candidate);
            }
        }

        // Fallback to the last candidate just in case of floating point precision issues
        return Optional.of(candidates.getLast());
    }
}
