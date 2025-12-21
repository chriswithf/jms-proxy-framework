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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


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
    private final AtomicLong inputCount = new AtomicLong(0);
    private final AtomicLong outputBatches = new AtomicLong(0);
    
    // O(1) flush tracking - avoids iterating over all buffers
    private final AtomicLong earliestBufferedTimestamp = new AtomicLong(Long.MAX_VALUE);
    private final AtomicInteger totalBufferedCount = new AtomicInteger(0);
    private final AtomicInteger largestBatchSize = new AtomicInteger(0);
    
    // ThreadLocal cache to avoid redundant parsing between shouldCondense() and addMessage()
    private static final ThreadLocal<ParseCache> PARSE_CACHE = ThreadLocal.withInitial(ParseCache::new);
    
    private static final class ParseCache {
        String content;
        String comparisonKey;
        long timestamp;
        
        void set(String content, String key) {
            this.content = content;
            this.comparisonKey = key;
            this.timestamp = System.currentTimeMillis();
        }
        
        void clear() {
            this.content = null;
            this.comparisonKey = null;
        }
        
        boolean isValid() {
            // Cache valid for 100ms max (safety for edge cases)
            return content != null && (System.currentTimeMillis() - timestamp) < 100;
        }
    }
    
    // Pre-allocated StringBuilder for JSON construction (reduces GC)
    private static final ThreadLocal<StringBuilder> JSON_BUILDER = 
        ThreadLocal.withInitial(() -> new StringBuilder(4096));
    
    private JsonMessageCondenser(Builder builder) {
        this.comparisonStrategy = builder.comparisonStrategy;
        this.windowMs = builder.windowMs;
        this.maxBatchSize = builder.maxBatchSize;
        this.timestampFields = builder.timestampFields;
        // Use HashMap/ArrayList as we are guarded by external lock in ProxyMessageProducer
        this.messageBuffer = new HashMap<>();
        this.objectMapper = JsonUtils.getObjectMapper();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public boolean shouldCondense(Message message) {
        String content = MessageUtils.getTextContent(message);
        if (content == null) {
            return false;
        }
        
        // Fast path: check if it looks like JSON without full parsing
        int len = content.length();
        if (len < 2) return false;
        
        // Find first non-whitespace char
        int start = 0;
        while (start < len && Character.isWhitespace(content.charAt(start))) start++;
        if (start >= len) return false;
        
        char first = content.charAt(start);
        if (first != '{' && first != '[') {
            return false;
        }
        
        // Pre-compute the comparison key and cache for addMessage()
        try {
            String key = comparisonStrategy.computeComparisonKey(content);
            if (key != null) {
                // Cache in ThreadLocal for addMessage() to reuse
                PARSE_CACHE.get().set(content, key);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public synchronized void addMessage(Message message) {
        inputCount.incrementAndGet();
        
        ParseCache cache = PARSE_CACHE.get();
        String content;
        String key;
        
        // Use cached values from shouldCondense() if available
        if (cache.isValid()) {
            content = cache.content;
            key = cache.comparisonKey;
            cache.clear();  // Clear after use
        } else {
            // Fallback if shouldCondense wasn't called first
            content = MessageUtils.getTextContent(message);
            if (content == null) {
                return;
            }
            key = comparisonStrategy.computeComparisonKey(content);
        }
        
        long now = System.currentTimeMillis();
        BufferedMessage buffered = new BufferedMessage(message, content, now);
        
        List<BufferedMessage> batch = messageBuffer.computeIfAbsent(key, k -> new ArrayList<>());
        batch.add(buffered);
        
        // Update O(1) tracking metrics
        int batchSize = batch.size();
        totalBufferedCount.incrementAndGet();
        earliestBufferedTimestamp.updateAndGet(existing -> Math.min(existing, now));
        largestBatchSize.updateAndGet(existing -> Math.max(existing, batchSize));
        
        // Guard debug logging to avoid string formatting overhead
        if (logger.isDebugEnabled()) {
            logger.debug("Buffered message with key hash: {}, batch size: {}", key.hashCode(), batchSize);
        }
    }
    
    @Override
    public synchronized List<CondensedMessageEnvelope> flush() {
        if (totalBufferedCount.get() == 0) {
            return Collections.emptyList();
        }
        
        List<CondensedMessageEnvelope> envelopes = new ArrayList<>();
        long now = System.currentTimeMillis();
        int flushedCount = 0;
        
        // Iterate over a snapshot of keys to avoid ConcurrentModificationException
        Iterator<Map.Entry<String, List<BufferedMessage>>> iterator = messageBuffer.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<BufferedMessage>> entry = iterator.next();
            List<BufferedMessage> messages = entry.getValue();
            
            if (messages == null || messages.isEmpty()) {
                iterator.remove();
                continue;
            }
            
            // Check if window expired or batch is full
            BufferedMessage first = messages.get(0);
            boolean windowExpired = (now - first.bufferedAt) >= windowMs;
            boolean batchFull = messages.size() >= maxBatchSize;
            
            if (windowExpired || batchFull) {
                int msgCount = messages.size();
                flushedCount += msgCount;
                
                // Create envelope with lazy content generation (reuse list, don't copy)
                List<BufferedMessage> snapshot = new ArrayList<>(messages);
                CondensedMessageEnvelope envelope = createLazyEnvelope(snapshot);
                envelopes.add(envelope);
                
                // Clear and remove
                messages.clear();
                iterator.remove();
                
                if (logger.isDebugEnabled()) {
                    logger.debug("Flushed {} messages into condensed envelope", msgCount);
                }
                outputBatches.incrementAndGet();
            }
        }
        
        // Update O(1) tracking after flush
        if (flushedCount > 0) {
            totalBufferedCount.addAndGet(-flushedCount);
            recalculateTrackingMetrics();
        }
        
        return envelopes;
    }
    
    // Recalculate tracking metrics after flush (called rarely)
    private void recalculateTrackingMetrics() {
        if (messageBuffer.isEmpty()) {
            earliestBufferedTimestamp.set(Long.MAX_VALUE);
            largestBatchSize.set(0);
        } else {
            long earliest = Long.MAX_VALUE;
            int largest = 0;
            for (List<BufferedMessage> batch : messageBuffer.values()) {
                if (!batch.isEmpty()) {
                    earliest = Math.min(earliest, batch.get(0).bufferedAt);
                    largest = Math.max(largest, batch.size());
                }
            }
            earliestBufferedTimestamp.set(earliest);
            largestBatchSize.set(largest);
        }
    }
    
    private CondensedMessageEnvelope createLazyEnvelope(List<BufferedMessage> messages) {
        List<OriginalMessageInfo> originalInfos = new ArrayList<>();
        for (BufferedMessage msg : messages) {
            originalInfos.add(OriginalMessageInfo.fromMessage(msg.message));
        }
        
        // Return envelope with lazy supplier for the heavy JSON work
        return new CondensedMessageEnvelope(() -> {
            List<Long> timestamps = new ArrayList<>();
            String baseContent = messages.get(0).content;
            
            for (BufferedMessage msg : messages) {
                // Extract timestamp from message content
                for (String tsField : timestampFields) {
                    String ts = JsonUtils.extractField(msg.content, tsField);
                    if (ts != null) {
                        try {
                            timestamps.add(Long.parseLong(ts));
                        } catch (NumberFormatException e) {
                            // Not a numeric timestamp
                        }
                        break;
                    }
                }
            }
            
            return createAggregatedContent(baseContent, timestamps, messages.size());
        }, originalInfos);
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
        // O(1) check using tracked metrics instead of O(n) iteration
        int buffered = totalBufferedCount.get();
        if (buffered == 0) {
            return false;
        }
        
        // Check if largest batch is full
        if (largestBatchSize.get() >= maxBatchSize) {
            return true;
        }
        
        // Check if window expired for earliest message
        long earliest = earliestBufferedTimestamp.get();
        if (earliest != Long.MAX_VALUE) {
            return (System.currentTimeMillis() - earliest) >= windowMs;
        }
        
        return false;
    }
    
    @Override
    public int getBufferedMessageCount() {
        // O(1) using tracked count instead of O(n) stream
        return totalBufferedCount.get();
    }
    
    @Override
    public void clear() {
        messageBuffer.clear();
        totalBufferedCount.set(0);
        earliestBufferedTimestamp.set(Long.MAX_VALUE);
        largestBatchSize.set(0);
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
