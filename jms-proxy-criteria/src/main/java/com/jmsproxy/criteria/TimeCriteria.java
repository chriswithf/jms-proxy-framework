package com.jmsproxy.criteria;

import com.jmsproxy.core.SendCriteria;
import jakarta.jms.Message;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time and rate-based criteria for message sending.
 */
public class TimeCriteria implements SendCriteria {
    
    private final TimeEvaluator evaluator;
    
    private TimeCriteria(TimeEvaluator evaluator) {
        this.evaluator = evaluator;
    }
    
    /**
     * Creates a criteria that only allows messages during specific hours.
     */
    public static TimeCriteria duringHours(int startHour, int endHour) {
        return new TimeCriteria(message -> {
            int currentHour = LocalTime.now().getHour();
            if (startHour <= endHour) {
                return currentHour >= startHour && currentHour < endHour;
            } else {
                // Handles overnight ranges like 22:00 - 06:00
                return currentHour >= startHour || currentHour < endHour;
            }
        });
    }
    
    /**
     * Creates a criteria that only allows messages during business hours (9-17).
     */
    public static TimeCriteria businessHours() {
        return duringHours(9, 17);
    }
    
    /**
     * Creates a criteria that only allows messages outside business hours.
     */
    public static SendCriteria outsideBusinessHours() {
        return businessHours().negate();
    }
    
    /**
     * Creates a rate limiter that allows N messages per second.
     */
    public static TimeCriteria rateLimit(int messagesPerSecond) {
        return new RateLimitCriteria(messagesPerSecond, 1000);
    }
    
    /**
     * Creates a rate limiter with custom window.
     */
    public static TimeCriteria rateLimit(int maxMessages, long windowMs) {
        return new RateLimitCriteria(maxMessages, windowMs);
    }
    
    /**
     * Creates a throttle that only allows one message every N milliseconds.
     */
    public static TimeCriteria throttle(long minIntervalMs) {
        return new ThrottleCriteria(minIntervalMs);
    }
    
    @Override
    public boolean evaluate(Message message) {
        return evaluator.evaluate(message);
    }
    
    @FunctionalInterface
    private interface TimeEvaluator {
        boolean evaluate(Message message);
    }
    
    /**
     * Rate limiting implementation using sliding window.
     */
    private static class RateLimitCriteria extends TimeCriteria {
        private final int maxMessages;
        private final long windowMs;
        private final AtomicInteger messageCount = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        
        RateLimitCriteria(int maxMessages, long windowMs) {
            super(null);
            this.maxMessages = maxMessages;
            this.windowMs = windowMs;
        }
        
        @Override
        public boolean evaluate(Message message) {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            
            if (now - start >= windowMs) {
                // Reset window
                windowStart.set(now);
                messageCount.set(1);
                return true;
            }
            
            return messageCount.incrementAndGet() <= maxMessages;
        }
    }
    
    /**
     * Throttle implementation ensuring minimum interval between messages.
     */
    private static class ThrottleCriteria extends TimeCriteria {
        private final long minIntervalMs;
        private final AtomicLong lastMessageTime = new AtomicLong(0);
        
        ThrottleCriteria(long minIntervalMs) {
            super(null);
            this.minIntervalMs = minIntervalMs;
        }
        
        @Override
        public boolean evaluate(Message message) {
            long now = System.currentTimeMillis();
            long last = lastMessageTime.get();
            
            if (now - last >= minIntervalMs) {
                lastMessageTime.set(now);
                return true;
            }
            return false;
        }
    }
}
