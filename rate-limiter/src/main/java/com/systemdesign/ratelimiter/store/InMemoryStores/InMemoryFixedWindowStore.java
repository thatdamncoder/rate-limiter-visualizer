package com.systemdesign.ratelimiter.store.InMemoryStores;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("fixedWindowStore")
@Profile("memory")
public class InMemoryFixedWindowStore<T> extends InMemoryStore<T> {
}
