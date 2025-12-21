package com.jmsproxy.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a condensed message that contains multiple original messages.
 * This envelope is used to wrap condensed messages for transmission.
 */
public class CondensedMessageEnvelope implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public static final String CONDENSED_MARKER = "_JMS_PROXY_CONDENSED_";
    public static final String CONDENSED_COUNT = "_JMS_PROXY_CONDENSED_COUNT_";
    public static final String CONDENSED_TIMESTAMPS = "_JMS_PROXY_CONDENSED_TIMESTAMPS_";
    public static final String CONDENSED_MESSAGE_IDS = "_JMS_PROXY_CONDENSED_MESSAGE_IDS_";
    
    private final String aggregatedContent;
    private final java.util.function.Supplier<String> contentSupplier;
    private final List<OriginalMessageInfo> originalMessages;
    private final long firstTimestamp;
    private final long lastTimestamp;
    private final int messageCount;
    
    public CondensedMessageEnvelope(String aggregatedContent, 
                                     List<OriginalMessageInfo> originalMessages) {
        this.aggregatedContent = aggregatedContent;
        this.contentSupplier = null;
        this.originalMessages = new ArrayList<>(originalMessages);
        this.messageCount = originalMessages.size();
        
        if (!originalMessages.isEmpty()) {
            this.firstTimestamp = originalMessages.stream()
                .mapToLong(OriginalMessageInfo::getTimestamp)
                .min()
                .orElse(System.currentTimeMillis());
            this.lastTimestamp = originalMessages.stream()
                .mapToLong(OriginalMessageInfo::getTimestamp)
                .max()
                .orElse(System.currentTimeMillis());
        } else {
            this.firstTimestamp = System.currentTimeMillis();
            this.lastTimestamp = System.currentTimeMillis();
        }
    }

    public CondensedMessageEnvelope(java.util.function.Supplier<String> contentSupplier, 
                                     List<OriginalMessageInfo> originalMessages) {
        this.aggregatedContent = null;
        this.contentSupplier = contentSupplier;
        this.originalMessages = new ArrayList<>(originalMessages);
        this.messageCount = originalMessages.size();
        
        if (!originalMessages.isEmpty()) {
            this.firstTimestamp = originalMessages.stream()
                .mapToLong(OriginalMessageInfo::getTimestamp)
                .min()
                .orElse(System.currentTimeMillis());
            this.lastTimestamp = originalMessages.stream()
                .mapToLong(OriginalMessageInfo::getTimestamp)
                .max()
                .orElse(System.currentTimeMillis());
        } else {
            this.firstTimestamp = System.currentTimeMillis();
            this.lastTimestamp = System.currentTimeMillis();
        }
    }
    
    public String getAggregatedContent() {
        if (aggregatedContent != null) {
            return aggregatedContent;
        }
        if (contentSupplier != null) {
            return contentSupplier.get();
        }
        return null;
    }
    
    public List<OriginalMessageInfo> getOriginalMessages() {
        return Collections.unmodifiableList(originalMessages);
    }
    
    public long getFirstTimestamp() {
        return firstTimestamp;
    }
    
    public long getLastTimestamp() {
        return lastTimestamp;
    }
    
    public int getMessageCount() {
        return messageCount;
    }
    
    public boolean isCondensed() {
        return messageCount > 1;
    }
}
