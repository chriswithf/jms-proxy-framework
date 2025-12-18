package com.jmsproxy.condenser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmsproxy.core.CondensedMessageEnvelope;
import com.jmsproxy.core.MessageComparisonStrategy;
import com.jmsproxy.core.MessageCondenser;
import com.jmsproxy.core.OriginalMessageInfo;
import com.jmsproxy.core.util.JsonUtils;
import com.jmsproxy.core.util.MessageUtils;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Condenser that aggregates JSON messages with identical content (excluding specified fields).
 */
public class JsonMessageCondenser implements MessageCondenser {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonMessageCondenser.class);
    
    private final MessageComparisonStrategy comparisonStrategy;
    private final Map<String, List<BufferedMessage>> messageBuffer;
    private final long windowMs;
    private final int maxBatchSize;
    private final Set<String> timestampFields;
    private final ObjectMapper objectMapper;
    
    // Stats
    private final java.util.concurrent.atomic.AtomicLong inputCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong outputBatches = new java.util.concurrent.atomic.AtomicLong(0);
    
    private JsonMessageCondenser(Builder builder) {
        this.comparisonStrategy = builder.comparisonStrategy;
        this.windowMs = builder.windowMs;
        this.maxBatchSize = builder.maxBatchSize;
        this.timestampFields = builder.timestampFields;
        this.messageBuffer = new ConcurrentHashMap<>();
        this.objectMapper = JsonUtils.getObjectMapper();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public boolean shouldCondense(Message message) {
        inputCount.incrementAndGet();
        String content = MessageUtils.getTextContent(message);
        return content != null && JsonUtils.isValidJson(content);
    }
    
    @Override
    public void addMessage(Message message) {
        String content = MessageUtils.getTextContent(message);
        if (content == null) {
            return;
        }
        
        String key = comparisonStrategy.computeComparisonKey(content);
        BufferedMessage buffered = new BufferedMessage(message, content, System.currentTimeMillis());
        
        messageBuffer.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                     .add(buffered);
        
        logger.debug("Buffered message with key: {}", key.hashCode());
    }
    
    @Override
    public List<CondensedMessageEnvelope> flush() {
        List<CondensedMessageEnvelope> envelopes = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        Iterator<Map.Entry<String, List<BufferedMessage>>> iterator = messageBuffer.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<String, List<BufferedMessage>> entry = iterator.next();
            List<BufferedMessage> messages = entry.getValue();
            
            synchronized (messages) {
                if (messages.isEmpty()) {
                    iterator.remove();
                    continue;
                }
                
                // Check if window expired or batch is full
                BufferedMessage first = messages.get(0);
                boolean windowExpired = (now - first.bufferedAt) >= windowMs;
                boolean batchFull = messages.size() >= maxBatchSize;
                
                if (windowExpired || batchFull) {
                    CondensedMessageEnvelope envelope = createEnvelope(messages);
                    envelopes.add(envelope);
                    messages.clear();
                    iterator.remove();
                    
                    logger.debug("Flushed {} messages into condensed envelope", envelope.getMessageCount());
                    outputBatches.incrementAndGet();
                }
            }
        }
        
        return envelopes;
    }
    
    private CondensedMessageEnvelope createEnvelope(List<BufferedMessage> messages) {
        List<OriginalMessageInfo> originalInfos = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        
        // Use the first message's content as the base
        String baseContent = messages.get(0).content;
        
        for (BufferedMessage msg : messages) {
            originalInfos.add(OriginalMessageInfo.fromMessage(msg.message));
            
            // Extract timestamp from message content
            for (String tsField : timestampFields) {
                String ts = JsonUtils.extractField(msg.content, tsField);
                if (ts != null) {
                    try {
                        timestamps.add(Long.parseLong(ts));
                    } catch (NumberFormatException e) {
                        // Not a numeric timestamp, store as string
                    }
                    break;
                }
            }
        }
        
        // Create aggregated content with timestamp array
        String aggregatedContent = createAggregatedContent(baseContent, timestamps, messages.size());
        
        return new CondensedMessageEnvelope(aggregatedContent, originalInfos);
    }
    
    private String createAggregatedContent(String baseContent, List<Long> timestamps, int count) {
        try {
            JsonNode baseNode = objectMapper.readTree(baseContent);
            if (baseNode.isObject()) {
                ObjectNode objectNode = (ObjectNode) baseNode;
                
                // Remove individual timestamp fields
                for (String tsField : timestampFields) {
                    objectNode.remove(tsField);
                }
                
                // Add condensed metadata
                ObjectNode metaNode = objectMapper.createObjectNode();
                metaNode.put("condensed", true);
                metaNode.put("count", count);
                
                if (!timestamps.isEmpty()) {
                    ArrayNode tsArray = objectMapper.createArrayNode();
                    timestamps.forEach(tsArray::add);
                    metaNode.set("originalTimestamps", tsArray);
                    metaNode.put("firstTimestamp", timestamps.stream().min(Long::compareTo).orElse(0L));
                    metaNode.put("lastTimestamp", timestamps.stream().max(Long::compareTo).orElse(0L));
                }
                
                objectNode.set("_condensedMeta", metaNode);
                
                return objectMapper.writeValueAsString(objectNode);
            }
        } catch (JsonProcessingException e) {
            logger.warn("Failed to create aggregated content", e);
        }
        return baseContent;
    }
    
    @Override
    public boolean hasMessagesToFlush() {
        long now = System.currentTimeMillis();
        
        for (List<BufferedMessage> messages : messageBuffer.values()) {
            synchronized (messages) {
                if (!messages.isEmpty()) {
                    BufferedMessage first = messages.get(0);
                    if ((now - first.bufferedAt) >= windowMs || messages.size() >= maxBatchSize) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    @Override
    public int getBufferedMessageCount() {
        return messageBuffer.values().stream()
                           .mapToInt(List::size)
                           .sum();
    }
    
    @Override
    public void clear() {
        messageBuffer.clear();
    }
    
    private record BufferedMessage(Message message, String content, long bufferedAt) {}
    
    @Override
    public Map<String, Long> getStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("inputMessages", inputCount.get());
        stats.put("outputBatches", outputBatches.get());
        return stats;
    }

    public static class Builder {
        private MessageComparisonStrategy comparisonStrategy = JsonFieldExclusionStrategy.excludeTimestamps();
        private long windowMs = 1000;
        private int maxBatchSize = 100;
        private Set<String> timestampFields = new HashSet<>(Arrays.asList(
            "timestamp", "time", "datetime", "ts", "createdAt", "created_at", 
            "eventTime", "event_time"
        ));
        
        public Builder comparisonStrategy(MessageComparisonStrategy strategy) {
            this.comparisonStrategy = strategy;
            return this;
        }
        
        public Builder windowMs(long windowMs) {
            this.windowMs = windowMs;
            return this;
        }
        
        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }
        
        public Builder timestampFields(String... fields) {
            this.timestampFields = new HashSet<>(Arrays.asList(fields));
            return this;
        }
        
        public Builder addTimestampField(String field) {
            this.timestampFields.add(field);
            return this;
        }
        
        public JsonMessageCondenser build() {
            return new JsonMessageCondenser(this);
        }
    }
}
