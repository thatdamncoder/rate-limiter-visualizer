package com.systemdesign.ratelimiter.store.activeconfig;

import tools.jackson.databind.ObjectMapper;
import com.systemdesign.ratelimiter.dto.RateLimiterInitRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis profile: persists the active init request in Redis so any instance can
 * reconstruct the same limiter. This is what lets the global limit hold across
 * several instances behind a load balancer.
 */
@Component
@Profile("redis")
public class RedisActiveConfigStore implements ActiveConfigStore {

    private static final String KEY = "rl:activeConfig";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisActiveConfigStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void save(RateLimiterInitRequest request) {
        try {
            redis.opsForValue().set(KEY, mapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist active config", e);
        }
    }

    @Override
    public RateLimiterInitRequest load() {
        String json = redis.opsForValue().get(KEY);
        if (json == null) return null;
        try {
            return mapper.readValue(json, RateLimiterInitRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load active config", e);
        }
    }
}
