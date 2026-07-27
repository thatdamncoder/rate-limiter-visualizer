package com.systemdesign.ratelimiter.store.activeconfig;

import com.systemdesign.ratelimiter.dto.RateLimiterInitRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Memory profile: single-instance behaviour, identical to the original app.
 * Nothing is shared; the controller's own field is the only source of truth.
 */
@Component
@Profile("memory")
public class NoOpActiveConfigStore implements ActiveConfigStore {
    @Override public void save(RateLimiterInitRequest request) { /* no-op */ }
    @Override public RateLimiterInitRequest load() { return null; }
}
