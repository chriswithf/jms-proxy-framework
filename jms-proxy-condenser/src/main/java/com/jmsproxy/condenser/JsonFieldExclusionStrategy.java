package com.jmsproxy.condenser;

import com.jmsproxy.core.MessageComparisonStrategy;
import com.jmsproxy.core.util.JsonUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * JSON-based comparison strategy that ignores specified fields (like timestamps).
 */
public class JsonFieldExclusionStrategy implements MessageComparisonStrategy {
    
    private final Set<String> excludeFields;
    
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
        if (!JsonUtils.isValidJson(content)) {
            return content;
        }
        return JsonUtils.normalizeExcludingFields(content, excludeFields);
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
