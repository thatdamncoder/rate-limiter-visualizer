package com.systemdesign.ratelimiter.store.InMemoryStores;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("slidingWindowCounterStore")
@Profile("memory")
public class InMemorySlidingWindowCounterStore<T> extends InMemoryStore<T> {
}
