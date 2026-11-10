package com.movie.waiting.service;

public interface FencingEpochProvider {

    boolean enabled();

    long currentEpoch();

    static FencingEpochProvider disabled() {
        return new FencingEpochProvider() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public long currentEpoch() {
                return 0;
            }
        };
    }
}
