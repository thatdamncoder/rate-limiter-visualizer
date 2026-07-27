package com.systemdesign.ratelimiter.controller;

import com.systemdesign.ratelimiter.dto.RateLimiterHitResponse;
import com.systemdesign.ratelimiter.dto.RateLimiterInitRequest;
import com.systemdesign.ratelimiter.dto.RateLimiterInitResponse;
import com.systemdesign.ratelimiter.service.factory.RateLimiterFactory;
import com.systemdesign.ratelimiter.store.activeconfig.ActiveConfigStore;
import com.systemdesign.ratelimiter.service.algorithm.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api")
public class RateLimiterController {

    // NOTE: single limiter for demo / visualizer
    private volatile RateLimiter rateLimiter;
    private final RateLimiterFactory factory;
    private final ActiveConfigStore activeConfigStore;

    public RateLimiterController(RateLimiterFactory factory, ActiveConfigStore activeConfigStore) {
        this.factory = factory;
        this.activeConfigStore = activeConfigStore;
    }

    @PostMapping("/init")
    public ResponseEntity<?> initLimiter(
            @RequestBody RateLimiterInitRequest request
    ){
        try{
            System.out.println(request.getRefillRate());
            this.rateLimiter = factory.createRateLimiter(request);
            activeConfigStore.save(request); // share config so other instances can rebuild
        } catch (Exception e) {
            System.out.println(e.getMessage());
            throw new RuntimeException(e);
        }

        System.out.println(request.getRefillRate() + " " + request.getBucketCapacity());
        return ResponseEntity.ok(
                new RateLimiterInitResponse(
                        true,
                        "Rate limiter initialized",
                        request.getAlgorithm()
                )
        );
    }

    // STEP 2: Fire request
    @GetMapping("/hit")
    //ResponseEntity<RateLimiterHitResponse>
    public ResponseEntity<?> hit(HttpServletRequest httpRequest) {

        // Multi-instance: if a /hit lands on an instance that never received /init,
        // rebuild the limiter from the shared (Redis) config. State is shared too,
        // so the limit stays global regardless of which instance serves the request.
        if (rateLimiter == null) {
            RateLimiterInitRequest shared = activeConfigStore.load();
            if (shared != null) {
                this.rateLimiter = factory.createRateLimiter(shared);
            }
        }

        if (rateLimiter == null) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new RateLimiterHitResponse(
                            false,
                            "Rate limiter not initialized",
                            System.currentTimeMillis(),
                            0,
                            0,
                            null
                    ));
        }

        String clientId = httpRequest.getRemoteAddr();
        RateLimiterHitResponse response = rateLimiter.hitEndpoint(clientId);

        HttpStatus status = response.accepted()
                ? HttpStatus.OK
                : HttpStatus.TOO_MANY_REQUESTS;

        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/reset")
    public ResponseEntity<?> resetLimiter() {

        if (rateLimiter == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        rateLimiter.reset();
        return ResponseEntity.ok().build();
    }
}
