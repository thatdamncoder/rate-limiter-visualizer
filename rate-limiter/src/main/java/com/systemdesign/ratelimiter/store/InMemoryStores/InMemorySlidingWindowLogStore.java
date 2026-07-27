package com.systemdesign.ratelimiter.store.InMemoryStores;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("slidingWindowLogStore")
@Profile("memory")
public class InMemorySlidingWindowLogStore <T> extends InMemoryStore<T> {
}
