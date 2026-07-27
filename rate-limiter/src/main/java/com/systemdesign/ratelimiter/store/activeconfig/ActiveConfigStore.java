package com.systemdesign.ratelimiter.store.activeconfig;

import com.systemdesign.ratelimiter.dto.RateLimiterInitRequest;

/**
 * Holds the currently active limiter configuration so that, in a multi-instance
 * deployment, an instance that never received the /init call can still rebuild the
 * correct limiter when a /hit lands on it.
 */
public interface ActiveConfigStore {
    void save(RateLimiterInitRequest request);
    RateLimiterInitRequest load();
}
