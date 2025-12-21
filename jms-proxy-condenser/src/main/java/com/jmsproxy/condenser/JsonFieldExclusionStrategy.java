package com.jmsproxy.condenser;

import com.jmsproxy.core.MessageComparisonStrategy;
import com.jmsproxy.core.util.JsonUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JSON-based comparison strategy that ignores specified fields (like timestamps).
 * Includes an LRU cache to avoid redundant parsing of identical content.
 */
public class JsonFieldExclusionStrategy implements MessageComparisonStrategy {
    
    private final Set<String> excludeFields;
    
    // LRU cache for comparison keys - avoids re-parsing identical JSON
    private static final int CACHE_SIZE = 1000;
    private final Map<Integer, String> keyCache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    
    public JsonFieldExclusionStrategy(Set<String> excludeFields) {
        this.excludeFields = new HashSet<>(excludeFields);
    }
    
    /**
     * Creates a strategy that excludes common timestamp fields.
     */
    public static JsonFieldExclusionStrategy excludeTimestamps() {
        return new Builder()
            .excludeField("timestamp")
            .excludeField("time")
            .excludeField("datetime")
            .excludeField("date")
            .excludeField("createdAt")
            .excludeField("created_at")
            .excludeField("updatedAt")
            .excludeField("updated_at")
            .excludeField("ts")
            .excludeField("eventTime")
            .excludeField("event_time")
            .build();
    }
    
    /**
     * Creates a strategy with custom fields to exclude.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public String computeComparisonKey(String content) {
        // Fast path: check cache first using content hash
        int contentHash = content.hashCode();
        String cached;
        synchronized (keyCache) {
            cached = keyCache.get(contentHash);
        }
        if (cached != null) {
            return cached;
        }
        
        // Slow path: parse and normalize
        if (!JsonUtils.looksLikeJson(content)) {
            return content;
        }
        
        String key = JsonUtils.normalizeExcludingFields(content, excludeFields);
        
        // Cache the result
        synchronized (keyCache) {
            keyCache.put(contentHash, key);
        }
        
        return key;
    }
    
    public static class Builder {
        private final Set<String> excludeFields = new HashSet<>();
        
        public Builder excludeField(String fieldName) {
            excludeFields.add(fieldName);
            return this;
        }
        
        public Builder excludeFields(String... fieldNames) {
            for (String field : fieldNames) {
                excludeFields.add(field);
            }
            return this;
        }
        
        public JsonFieldExclusionStrategy build() {
            return new JsonFieldExclusionStrategy(excludeFields);
        }
    }
}
