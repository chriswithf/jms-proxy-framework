package com.jmsproxy.core;

/**
 * Strategy for comparing message content to determine similarity.
 * Used by condensers to group messages that should be aggregated.
 */
@FunctionalInterface
public interface MessageComparisonStrategy {
    
    /**
     * Computes a comparison key for the message content.
     * Messages with the same key are considered similar and can be condensed.
     * 
     * @param content The message content
     * @return A key representing the message's identity (excluding volatile fields like timestamp)
     */
    String computeComparisonKey(String content);
    
    /**
     * Determines if two message contents are similar enough to condense.
     * 
     * @param content1 First message content
     * @param content2 Second message content
     * @return true if messages can be condensed together
     */
    default boolean areSimilar(String content1, String content2) {
        return computeComparisonKey(content1).equals(computeComparisonKey(content2));
    }
}
