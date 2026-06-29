package com.stevesarmy.util;

import java.time.Duration;
import java.time.Instant;

public class RateLimiter {
    private static final long DEFAULT_MS_TO_REGENERATE = 500;
    private static final long DEFAULT_LIMIT = 5;
    
    private static Duration timeToRegenerate = Duration.ofMillis(DEFAULT_MS_TO_REGENERATE);
    private static Duration timeWindow = Duration.ofMillis(DEFAULT_MS_TO_REGENERATE * DEFAULT_LIMIT);
    
    private Instant startTime = null;
    
    public static void setRates(long msToRegenerate, long limit) {
        timeToRegenerate = Duration.ofMillis(msToRegenerate);
        timeWindow = Duration.ofMillis(msToRegenerate * limit);
    }
    
    public boolean checkExceeded() {
        if (startTime == null) {
            startTime = Instant.now().minus(timeWindow).plus(timeToRegenerate);
            return false;
        }
        
        Instant now = Instant.now();
        Duration elapsed = Duration.between(startTime, now);
        
        if (elapsed.compareTo(timeWindow) > 0) {
            elapsed = timeWindow;
        }
        
        Duration leftOver = elapsed.minus(timeToRegenerate);
        
        if (leftOver.isNegative()) {
            return true;
        }
        
        startTime = now.minus(leftOver);
        return false;
    }
    
    public void reset() {
        startTime = null;
    }
}