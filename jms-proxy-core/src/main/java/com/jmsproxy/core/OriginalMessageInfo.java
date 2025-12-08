package com.jmsproxy.core;

import jakarta.jms.Message;

/**
 * Represents an original message with its metadata before condensing.
 * Used for reconstructing messages on the consumer side.
 */
public class OriginalMessageInfo {
    
    private final String messageId;
    private final long timestamp;
    private final String correlationId;
    private final int priority;
    private final long expiration;
    private final String type;
    
    public OriginalMessageInfo(String messageId, long timestamp, String correlationId, 
                                int priority, long expiration, String type) {
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.correlationId = correlationId;
        this.priority = priority;
        this.expiration = expiration;
        this.type = type;
    }
    
    public static OriginalMessageInfo fromMessage(Message message) {
        try {
            return new OriginalMessageInfo(
                message.getJMSMessageID(),
                message.getJMSTimestamp(),
                message.getJMSCorrelationID(),
                message.getJMSPriority(),
                message.getJMSExpiration(),
                message.getJMSType()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract message info", e);
        }
    }
    
    public String getMessageId() {
        return messageId;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public int getPriority() {
        return priority;
    }
    
    public long getExpiration() {
        return expiration;
    }
    
    public String getType() {
        return type;
    }
}
