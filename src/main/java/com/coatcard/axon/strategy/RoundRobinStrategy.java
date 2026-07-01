package com.coatcard.axon.strategy;

import com.coatcard.axon.redis.RedisPairEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RoundRobinStrategy implements SchedulingStrategy {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "ROUND_ROBIN";
    }

    @Override
    public Optional<RedisPairEntry> select(List<RedisPairEntry> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        RedisPairEntry sample = candidates.getFirst();
        String counterKey = sample.getProvider().toLowerCase() + ":" + sample.getModelId(); // Use modelId or provider + model for uniqueness
        AtomicInteger counter = counters.computeIfAbsent(counterKey, k -> new AtomicInteger(0));

        int index = Math.abs(counter.getAndIncrement()) % candidates.size();
        return Optional.of(candidates.get(index));
    }
}
