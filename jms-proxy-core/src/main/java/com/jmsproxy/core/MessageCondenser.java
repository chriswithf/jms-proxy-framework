package com.jmsproxy.core;

import jakarta.jms.Message;
import java.util.List;

/**
 * Interface for message condensers that aggregate multiple similar messages.
 * Implementations define how messages are compared and merged.
 */
public interface MessageCondenser {
    
    /**
     * Determines if a message can be condensed with existing buffered messages.
     * 
     * @param message The incoming message
     * @return true if the message should be buffered for condensing
     */
    boolean shouldCondense(Message message);
    
    /**
     * Adds a message to the condenser buffer.
     * 
     * @param message The message to buffer
     */
    void addMessage(Message message);
    
    /**
     * Flushes all buffered messages and returns condensed envelopes.
     * 
     * @return List of condensed message envelopes
     */
    List<CondensedMessageEnvelope> flush();
    
    /**
     * Checks if there are messages ready to be flushed.
     * 
     * @return true if messages are ready for flushing
     */
    boolean hasMessagesToFlush();
    
    /**
     * Gets the current number of buffered messages.
     * 
     * @return The count of buffered messages
     */
    int getBufferedMessageCount();
    
    /**
     * Clears all buffered messages without sending.
     */
    void clear();
}
