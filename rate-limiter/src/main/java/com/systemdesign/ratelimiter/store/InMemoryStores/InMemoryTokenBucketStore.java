package com.systemdesign.ratelimiter.store.InMemoryStores;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("tokenBucketStore")
@Profile("memory")
public class InMemoryTokenBucketStore<T> extends InMemoryStore<T> {

}
