package com.jmsproxy.criteria;

import com.jmsproxy.core.SendCriteria;
import com.jmsproxy.core.util.MessageUtils;
import jakarta.jms.Message;

/**
 * Criteria based on message priority.
 */
public class PriorityCriteria implements SendCriteria {
    
    private final int minPriority;
    private final int maxPriority;
    
    public PriorityCriteria(int minPriority, int maxPriority) {
        this.minPriority = minPriority;
        this.maxPriority = maxPriority;
    }
    
    public static PriorityCriteria atLeast(int minPriority) {
        return new PriorityCriteria(minPriority, 9);
    }
    
    public static PriorityCriteria atMost(int maxPriority) {
        return new PriorityCriteria(0, maxPriority);
    }
    
    public static PriorityCriteria exactly(int priority) {
        return new PriorityCriteria(priority, priority);
    }
    
    public static PriorityCriteria range(int min, int max) {
        return new PriorityCriteria(min, max);
    }
    
    @Override
    public boolean evaluate(Message message) {
        try {
            int priority = message.getJMSPriority();
            return priority >= minPriority && priority <= maxPriority;
        } catch (Exception e) {
            return false;
        }
    }
}
