package com.systemdesign.ratelimiter.store.InMemoryStores;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("leakyBucketStore")
@Profile("memory")
public class InMemoryLeakyBucketStore<T> extends InMemoryStore<T> {
}
