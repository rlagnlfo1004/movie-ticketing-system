package com.movie.waiting.repository;

import com.movie.waiting.repository.RedisAdmissionLockRepository.AdmissionLockStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class InMemoryAdmissionLockStore implements AdmissionLockStore {

    private final Map<String, String> owners = new HashMap<>();

    @Override
    public synchronized boolean acquire(String key, String ownerId, Duration ttl) {
        if (owners.containsKey(key)) {
            return false;
        }
        owners.put(key, ownerId);
        return true;
    }

    @Override
    public synchronized boolean release(String key, String ownerId) {
        if (!ownerId.equals(owners.get(key))) {
            return false;
        }
        owners.remove(key);
        return true;
    }

    public synchronized void hold(String key, String ownerId) {
        owners.put(key, ownerId);
    }
}
