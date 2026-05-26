package com.movie.waiting.repository;

import com.movie.waiting.repository.RedisQueueRepository.QueueStore;
import com.movie.waiting.repository.RedisQueueRepository.ScoredMember;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryQueueStore implements QueueStore {

    private final Map<String, Map<String, Double>> queues = new HashMap<>();

    @Override
    public synchronized boolean addIfAbsent(String key, String token, double score) {
        Map<String, Double> queue = queues.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        if (queue.containsKey(token)) {
            return false;
        }
        queue.put(token, score);
        return true;
    }

    @Override
    public synchronized Double score(String key, String token) {
        return queues.getOrDefault(key, Map.of()).get(token);
    }

    @Override
    public synchronized Long rank(String key, String token) {
        List<String> tokens = queues.getOrDefault(key, Map.of()).entrySet().stream()
                .sorted(Comparator
                        .comparingDouble((Map.Entry<String, Double> entry) -> entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();

        int index = tokens.indexOf(token);
        return index < 0 ? null : (long) index;
    }

    @Override
    public synchronized Long cardinality(String key) {
        return (long) queues.getOrDefault(key, Map.of()).size();
    }

    @Override
    public synchronized Collection<ScoredMember> popMin(String key, long count) {
        Map<String, Double> queue = queues.getOrDefault(key, Map.of());
        List<ScoredMember> popped = queue.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(count)
                .map(entry -> new ScoredMember(entry.getKey(), entry.getValue()))
                .toList();

        Map<String, Double> mutableQueue = queues.get(key);
        if (mutableQueue != null) {
            for (ScoredMember member : popped) {
                mutableQueue.remove(member.value());
            }
        }

        return new ArrayList<>(popped);
    }
}
