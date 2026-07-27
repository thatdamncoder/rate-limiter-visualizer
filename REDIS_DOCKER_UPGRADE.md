# Redis + Docker Upgrade — Rate Limiter Visualizer

This document explains the upgrade from the original **single-JVM, in-memory** rate
limiter to a **distributed, Redis-backed, Dockerized** service. It covers what the
previous version did, exactly what changed and why, and how to run and prove it.

The public API (`/api/init`, `/api/hit`, `/api/reset`) and the frontend are unchanged.
Only the **storage layer underneath** changed.

---

## 1. What the previous version did

The original backend is a clean, extensible design: a `RateLimiter` interface with five
implementations (Token Bucket, Fixed Window, Sliding Window Log, Sliding Window Counter,
Leaky Bucket), each created on demand by a `RateLimiterFactory`. Crucially, every
algorithm keeps its state behind a shared abstraction:

```java
public interface RateLimiterStore<T> {
    T compute(String key, BiFunction<String, T, T> remappingFunction);
    void reset();
}
```

The only implementation was `InMemoryStore<T>`, backed by a `ConcurrentHashMap`. The
algorithm logic lives inside the `remappingFunction` (a Java lambda), and
`ConcurrentHashMap.compute` runs that lambda **atomically per key** — but only within a
single JVM.

### Two real limitations of that design

1. **Not actually distributed.** Run two copies behind a load balancer and each has its
   own `ConcurrentHashMap`. A limit of "5 requests / 10s" silently becomes "5 **per
   instance**". The résumé word "distributed" wasn't yet true.

2. **Atomicity is JVM-local.** `ConcurrentHashMap.compute` guarantees no two threads
   interleave *on one instance*, but nothing coordinates across instances, so the
   read-modify-write can race once you scale out.

There was also a smaller issue that only shows up when you scale: the controller stores
the active limiter in a per-instance field set by `/init`, so an instance that never
received `/init` would reject `/hit` with "not initialized".

The upgrade fixes all three by moving **state and active config into Redis**, shared by
every instance.

---

## 2. What changed (and why)

### 2.1 A Redis implementation of the existing `RateLimiterStore<T>`

The whole upgrade plugs into the abstraction the project already had. A new
`RedisRateLimiterStore<T>` implements `RateLimiterStore<T>`:

- **State is stored as JSON in Redis** (one key per client, e.g.
  `rl:tokenBucket:127.0.0.1`), so every instance reads and writes the *same* state and
  the limit is enforced **globally**.
- **Cross-instance atomicity** is provided by a short-lived **per-key distributed lock**
  (`SET key token NX PX 5000`, released with a compare-and-delete Lua script so a client
  only releases a lock it still owns). The `read → apply remappingFunction → write`
  sequence runs while holding that lock, which is the distributed equivalent of what
  `ConcurrentHashMap.compute` did in one JVM.
- **Every key gets a TTL** so idle clients are reclaimed automatically (no leak).

Why a lock instead of rewriting each algorithm in Lua? Because the algorithm logic in
this project lives in Java lambdas passed to `store.compute(...)`. Keeping the lock in
the store means **all five algorithms stay exactly as written** and just gain
distribution — a minimal, low-risk change. (Pushing each algorithm into a Lua script so
Redis performs the decision entirely server-side is faster and lock-free; it's noted
below as a future optimization.)

The five Redis stores are wired in `RedisStoreConfig` under the `redis` profile, reusing
the **same bean names** the factory already qualifies against (`fixedWindowStore`,
`tokenBucketStore`, …), so the factory needed no change. Each store gets its own key
namespace and a Jackson `JavaType` describing the concrete state it serializes
(records, an array of records for the sliding-window counter, and a `Deque<Long>` for
the sliding-window log).

### 2.2 Active config shared via Redis (so multi-instance actually works)

A tiny `ActiveConfigStore` abstraction was added with two implementations:

- `NoOpActiveConfigStore` (`memory` profile) — returns nothing; single-instance behaviour
  is identical to the original app.
- `RedisActiveConfigStore` (`redis` profile) — persists the `/init` request in Redis.

The controller now saves the config on `/init` and, on `/hit`, if this instance has no
limiter yet, rebuilds it from the shared config. Combined with shared state, this makes
the global limit hold no matter which instance nginx routes a request to.

### 2.3 The original in-memory path is preserved behind a Spring profile

- `redis` profile (**default**): Redis-backed stores + shared config.
- `memory` profile: the original `InMemoryStore` beans — no Redis required.

Switch with `RL_PROFILE=memory` (or `--spring.profiles.active=memory`). The two
implementations sit side by side for easy comparison.

### 2.4 Docker

- A multi-stage `Dockerfile` builds with Maven on **JDK 25** (matching `pom.xml`) and
  ships a small JRE runtime image.
- `docker-compose.yml` — Redis + one app instance (`docker compose up --build`).
- `docker-compose.scale.yml` — Redis + N app instances behind nginx, which is what
  demonstrates the distributed guarantee.

---

## 3. File-by-file summary

**New files**
```
rate-limiter/src/main/java/.../store/RedisStores/RedisRateLimiterStore.java   # Redis RateLimiterStore<T> + distributed lock
rate-limiter/src/main/java/.../config/RedisStoreConfig.java                   # 5 qualified Redis store beans (redis profile)
rate-limiter/src/main/java/.../store/activeconfig/ActiveConfigStore.java
rate-limiter/src/main/java/.../store/activeconfig/NoOpActiveConfigStore.java  # memory profile
rate-limiter/src/main/java/.../store/activeconfig/RedisActiveConfigStore.java # redis profile
rate-limiter/Dockerfile
rate-limiter/.dockerignore
docker-compose.yml
docker-compose.scale.yml
nginx/nginx.conf
```

**Changed files**
```
rate-limiter/pom.xml                                   # + spring-boot-starter-data-redis
rate-limiter/src/main/resources/application.properties # + profile + redis host/port + server.port
rate-limiter/src/main/java/.../controller/RateLimiterController.java  # inject ActiveConfigStore; save on /init; lazy rebuild on /hit
rate-limiter/src/main/java/.../store/InMemoryStores/InMemory*Store.java (x5)  # + @Profile("memory")
```

**Untouched** — the `RateLimiter` interface, all five algorithm classes and their state
records, the factory, the DTOs, and `InMemoryStore<T>`. The algorithms didn't change at
all; only where their state is stored did.

---

## 4. How to run

### Single instance
```bash
docker compose up --build
# backend: http://localhost:8080
```

### Distributed proof (3 instances behind nginx)
```bash
docker compose -f docker-compose.scale.yml up --build --scale app=3
# all traffic -> http://localhost:8080 (nginx round-robins across the 3 app containers)
```

### No Docker / no Redis (original behaviour)
```bash
cd rate-limiter
RL_PROFILE=memory ./mvnw spring-boot:run
```

---

## 5. How to prove the distributed limit holds

Because `/hit` identifies the client by remote IP (and behind nginx that's the proxy's
IP), all requests count as one client — perfect for showing a single global limit.

```bash
# 1) initialize: 10 requests / 10s, fixed window
curl -s -X POST http://localhost:8080/api/init \
  -H "Content-Type: application/json" \
  -d '{"algorithm":"FIXED_WINDOW","maxRequests":10,"windowSize":10}'

# 2) fire 30 requests
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/hit
done | sort | uniq -c
# Expect ~10 lines of "200" and ~20 lines of "429", regardless of instance count.

# 3) reset
curl -s -X POST http://localhost:8080/api/reset
```

Run the same test on the **old** in-memory build with 3 instances and you'd see up to
~30 allowed — the difference this upgrade fixes.

---

## 6. Honest limitations / good things to say in an interview

- The per-key distributed lock adds a small amount of latency and is a pessimistic
  approach; the faster, lock-free alternative is to reimplement each algorithm as a Redis
  Lua script so the decision happens atomically server-side (one round trip, no lock).
- `reset()` uses `KEYS` for simplicity; use `SCAN` in production.
- Redis is now a dependency and a single point of failure — the real next step is Redis
  Sentinel/Cluster for HA and a deliberate fail-open vs fail-closed policy if Redis is
  unreachable.
- Config changes propagate lazily: an instance rebuilds its limiter from shared config
  only when its local copy is null. For the demo flow (init once, then hit) this is fine.
