package com.jmsproxy.core;

import jakarta.jms.Message;

/**
 * Interface for send criteria that determine whether a message should be sent.
 * Implementations can define custom logic for filtering or modifying messages.
 */
@FunctionalInterface
public interface SendCriteria {
    
    /**
     * Evaluates whether a message meets the criteria for sending.
     * 
     * @param message The message to evaluate
     * @return true if the message should be sent, false to block
     */
    boolean evaluate(Message message);
    
    /**
     * Returns a criteria that represents the logical AND of this and another criteria.
     * 
     * @param other The other criteria to AND with
     * @return A combined criteria
     */
    default SendCriteria and(SendCriteria other) {
        return message -> this.evaluate(message) && other.evaluate(message);
    }
    
    /**
     * Returns a criteria that represents the logical OR of this and another criteria.
     * 
     * @param other The other criteria to OR with
     * @return A combined criteria
     */
    default SendCriteria or(SendCriteria other) {
        return message -> this.evaluate(message) || other.evaluate(message);
    }
    
    /**
     * Returns a criteria that represents the logical NOT of this criteria.
     * 
     * @return A negated criteria
     */
    default SendCriteria negate() {
        return message -> !this.evaluate(message);
    }
    
    /**
     * Returns a criteria that always allows sending.
     * 
     * @return An always-true criteria
     */
    static SendCriteria alwaysAllow() {
        return message -> true;
    }
    
    /**
     * Returns a criteria that always blocks sending.
     * 
     * @return An always-false criteria
     */
    static SendCriteria alwaysBlock() {
        return message -> false;
    }
}
