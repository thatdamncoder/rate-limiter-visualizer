package com.systemdesign.ratelimiter.store.RedisStores;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import com.systemdesign.ratelimiter.store.RateLimiterStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Redis-backed drop-in for {@link RateLimiterStore}. State for each client key is
 * stored as JSON in Redis, so EVERY app instance reads and writes the SAME state —
 * the rate limit is enforced globally instead of per-JVM.
 *
 * The original in-memory store relied on {@code ConcurrentHashMap.compute} for
 * atomic read-modify-write, which only holds within a single JVM. Here the
 * algorithm logic still runs in Java (inside the supplied BiFunction), so we make
 * the whole read -> apply -> write sequence atomic ACROSS instances with a
 * short-lived per-key distributed lock (SET NX PX + compare-and-delete unlock).
 *
 * (An even faster design pushes each algorithm into a Lua script so Redis does the
 * whole decision atomically server-side; that would mean rewriting the algorithms
 * and is noted as a future optimization in REDIS_DOCKER_UPGRADE.md.)
 */
public class RedisRateLimiterStore<T> implements RateLimiterStore<T> {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final JavaType stateType;
    private final String prefix;

    // Compare-and-delete so a client only releases a lock it still owns.
    private static final RedisScript<Long> UNLOCK = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final long STATE_TTL_MS = 3_600_000L; // reclaim idle clients after 1h
    private static final long LOCK_TTL_MS  = 5_000L;     // lock auto-expires if a holder dies
    private static final long LOCK_WAIT_MS = 2_000L;     // max time to wait for the lock
    private static final long LOCK_RETRY_MS = 5L;

    public RedisRateLimiterStore(StringRedisTemplate redis,
                                 ObjectMapper mapper,
                                 JavaType stateType,
                                 String prefix) {
        this.redis = redis;
        this.mapper = mapper;
        this.stateType = stateType;
        this.prefix = prefix;
    }

    private String stateKey(String key) { return prefix + ":" + key; }
    private String lockKey(String key)  { return prefix + ":lock:" + key; }

    @Override
    public T compute(String key, BiFunction<String, T, T> remappingFunction) {
        String lockKey = lockKey(key);
        String token = UUID.randomUUID().toString();
        boolean locked = acquire(lockKey, token);
        try {
            T current = read(stateKey(key));
            T next = remappingFunction.apply(key, current);
            if (next == null) {
                redis.delete(stateKey(key));
            } else {
                write(stateKey(key), next);
            }
            return next;
        } finally {
            if (locked) {
                redis.execute(UNLOCK, List.of(lockKey), token);
            }
        }
    }

    private boolean acquire(String lockKey, String token) {
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            Boolean ok = redis.opsForValue()
                    .setIfAbsent(lockKey, token, Duration.ofMillis(LOCK_TTL_MS));
            if (Boolean.TRUE.equals(ok)) return true;
            try {
                Thread.sleep(LOCK_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Lock contention timed out (rare); proceed so a request is never dropped.
        return false;
    }

    private T read(String stateKey) {
        String json = redis.opsForValue().get(stateKey);
        if (json == null) return null;
        try {
            return mapper.readValue(json, stateType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize state at " + stateKey, e);
        }
    }

    private void write(String stateKey, T value) {
        try {
            String json = mapper.writeValueAsString(value);
            redis.opsForValue().set(stateKey, json, Duration.ofMillis(STATE_TTL_MS));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize state at " + stateKey, e);
        }
    }

    @Override
    public void reset() {
        // Clears all state (and any stray locks) for this algorithm.
        // NOTE: keys() is fine for a demo; prefer SCAN in production.
        Set<String> keys = redis.keys(prefix + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
