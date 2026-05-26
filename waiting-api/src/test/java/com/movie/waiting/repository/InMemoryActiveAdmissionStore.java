package com.movie.waiting.repository;

import com.movie.waiting.repository.RedisActiveAdmissionRepository.ActiveAdmissionStore;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryActiveAdmissionStore implements ActiveAdmissionStore {

    private final Map<String, String> activeValues = new HashMap<>();
    private final Map<String, Map<String, Double>> indexes = new HashMap<>();

    @Override
    public synchronized boolean setActiveIfAbsent(String key, String token, Duration ttl) {
        if (activeValues.containsKey(key)) {
            return false;
        }
        activeValues.put(key, token);
        return true;
    }

    @Override
    public synchronized boolean exists(String key) {
        return activeValues.containsKey(key);
    }

    @Override
    public synchronized void addToActiveIndex(String indexKey, String token, double expiresAtMillis) {
        indexes.computeIfAbsent(indexKey, ignored -> new HashMap<>()).put(token, expiresAtMillis);
    }

    @Override
    public synchronized Long indexCardinality(String indexKey) {
        return (long) indexes.getOrDefault(indexKey, Map.of()).size();
    }

    @Override
    public synchronized Collection<String> rangeByExpiresAt(String indexKey, double min, double max) {
        return indexes.getOrDefault(indexKey, Map.of()).entrySet().stream()
                .filter(entry -> entry.getValue() >= min && entry.getValue() <= max)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public synchronized void removeFromActiveIndex(String indexKey, String token) {
        Map<String, Double> index = indexes.get(indexKey);
        if (index != null) {
            index.remove(token);
        }
    }

    public synchronized void expire(String activeKey) {
        activeValues.remove(activeKey);
    }

    public synchronized List<String> indexedTokens(String indexKey) {
        return List.copyOf(indexes.getOrDefault(indexKey, Map.of()).keySet());
    }
}
