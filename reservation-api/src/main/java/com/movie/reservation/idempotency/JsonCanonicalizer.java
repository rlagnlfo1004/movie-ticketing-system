package com.movie.reservation.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonCanonicalizer {

    private final ObjectMapper objectMapper;

    String canonicalize(byte[] body) {
        try {
            Object value = objectMapper.readValue(body, Object.class);
            return objectMapper.writeValueAsString(sort(value));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Request body must be valid JSON", exception);
        }
    }

    private Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sort(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sort).toList();
        }
        return value;
    }
}
