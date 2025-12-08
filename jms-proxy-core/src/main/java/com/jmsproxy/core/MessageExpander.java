package com.jmsproxy.core;

import jakarta.jms.Message;
import java.util.List;

/**
 * Interface for message expanders that reconstruct original messages from condensed envelopes.
 * Used on the consumer side to transparently unpack condensed messages.
 */
public interface MessageExpander {
    
    /**
     * Determines if a message is a condensed envelope.
     * 
     * @param message The message to check
     * @return true if the message is condensed
     */
    boolean isCondensed(Message message);
    
    /**
     * Expands a condensed message back into individual messages.
     * 
     * @param message The condensed message
     * @return List of reconstructed original messages
     */
    List<Message> expand(Message message);
    
    /**
     * Extracts the condensed envelope from a message.
     * 
     * @param message The condensed message
     * @return The envelope containing metadata
     */
    CondensedMessageEnvelope extractEnvelope(Message message);
}
