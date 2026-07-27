package com.systemdesign.ratelimiter.config;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import com.systemdesign.ratelimiter.service.algorithm.FixedWindow.FixedWindowState;
import com.systemdesign.ratelimiter.service.algorithm.LeakyBucket.LeakyBucketState;
import com.systemdesign.ratelimiter.service.algorithm.SlidingWindow.SlidingWindowCounter.SlidingWindowCounterState;
import com.systemdesign.ratelimiter.service.algorithm.TokenBucket.TokenBucketState;
import com.systemdesign.ratelimiter.store.RateLimiterStore;
import com.systemdesign.ratelimiter.store.RedisStores.RedisRateLimiterStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Wires the five Redis-backed stores under the "redis" profile, using the SAME
 * bean names the RateLimiterFactory qualifies against ("fixedWindowStore", ...),
 * so switching from in-memory to Redis needs no change in the factory.
 *
 * Each store gets a distinct key namespace and a Jackson JavaType describing the
 * concrete state it serializes.
 */
@Configuration
@Profile("redis")
public class RedisStoreConfig {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisStoreConfig(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private <T> RedisRateLimiterStore<T> store(String prefix, JavaType type) {
        return new RedisRateLimiterStore<>(redis, mapper, type, prefix);
    }

    @Bean("fixedWindowStore")
    public RateLimiterStore<FixedWindowState> fixedWindowStore() {
        return store("rl:fixedWindow",
                mapper.getTypeFactory().constructType(FixedWindowState.class));
    }

    @Bean("tokenBucketStore")
    public RateLimiterStore<TokenBucketState> tokenBucketStore() {
        return store("rl:tokenBucket",
                mapper.getTypeFactory().constructType(TokenBucketState.class));
    }

    @Bean("leakyBucketStore")
    public RateLimiterStore<LeakyBucketState> leakyBucketStore() {
        return store("rl:leakyBucket",
                mapper.getTypeFactory().constructType(LeakyBucketState.class));
    }

    @Bean("slidingWindowCounterStore")
    public RateLimiterStore<SlidingWindowCounterState[]> slidingWindowCounterStore() {
        return store("rl:slidingWindowCounter",
                mapper.getTypeFactory().constructArrayType(SlidingWindowCounterState.class));
    }

    @Bean("slidingWindowLogStore")
    public RateLimiterStore<Deque<Long>> slidingWindowLogStore() {
        JavaType type = mapper.getTypeFactory()
                .constructCollectionType(ArrayDeque.class, Long.class);
        return store("rl:slidingWindowLog", type);
    }
}
