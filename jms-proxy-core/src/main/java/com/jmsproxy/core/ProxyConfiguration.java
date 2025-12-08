package com.jmsproxy.core;

/**
 * Configuration class for the JMS Proxy Framework.
 * Holds all configuration parameters that can be set via the builder.
 */
public class ProxyConfiguration {
    
    private final long condenserWindowMs;
    private final int condenserMaxBatchSize;
    private final boolean condenserEnabled;
    private final boolean criteriaEnabled;
    private final long flushIntervalMs;
    private final boolean asyncProcessing;
    private final int asyncThreadPoolSize;
    private final boolean preserveMessageOrder;
    private final boolean enableMetrics;
    
    private ProxyConfiguration(Builder builder) {
        this.condenserWindowMs = builder.condenserWindowMs;
        this.condenserMaxBatchSize = builder.condenserMaxBatchSize;
        this.condenserEnabled = builder.condenserEnabled;
        this.criteriaEnabled = builder.criteriaEnabled;
        this.flushIntervalMs = builder.flushIntervalMs;
        this.asyncProcessing = builder.asyncProcessing;
        this.asyncThreadPoolSize = builder.asyncThreadPoolSize;
        this.preserveMessageOrder = builder.preserveMessageOrder;
        this.enableMetrics = builder.enableMetrics;
    }
    
    public long getCondenserWindowMs() {
        return condenserWindowMs;
    }
    
    public int getCondenserMaxBatchSize() {
        return condenserMaxBatchSize;
    }
    
    public boolean isCondenserEnabled() {
        return condenserEnabled;
    }
    
    public boolean isCriteriaEnabled() {
        return criteriaEnabled;
    }
    
    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }
    
    public boolean isAsyncProcessing() {
        return asyncProcessing;
    }
    
    public int getAsyncThreadPoolSize() {
        return asyncThreadPoolSize;
    }
    
    public boolean isPreserveMessageOrder() {
        return preserveMessageOrder;
    }
    
    public boolean isEnableMetrics() {
        return enableMetrics;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Returns a default configuration with sensible defaults.
     * - Condenser: enabled, 1s window, max 100 messages per batch
     * - Criteria: enabled
     * - Flush interval: 500ms
     */
    public static ProxyConfiguration defaults() {
        return builder().build();
    }
    
    /**
     * Returns a configuration with condensing disabled (pass-through mode).
     */
    public static ProxyConfiguration passThrough() {
        return builder()
            .condenserEnabled(false)
            .criteriaEnabled(false)
            .build();
    }
    
    /**
     * Returns a configuration optimized for high-throughput scenarios.
     */
    public static ProxyConfiguration highThroughput() {
        return builder()
            .condenserWindowMs(500)
            .condenserMaxBatchSize(500)
            .flushIntervalMs(250)
            .asyncProcessing(true)
            .asyncThreadPoolSize(8)
            .build();
    }
    
    public static class Builder {
        private long condenserWindowMs = 1000; // Default 1 second window
        private int condenserMaxBatchSize = 100;
        private boolean condenserEnabled = true;
        private boolean criteriaEnabled = true;
        private long flushIntervalMs = 500;
        private boolean asyncProcessing = false;
        private int asyncThreadPoolSize = 4;
        private boolean preserveMessageOrder = true;
        private boolean enableMetrics = false;
        
        public Builder condenserWindowMs(long windowMs) {
            this.condenserWindowMs = windowMs;
            return this;
        }
        
        public Builder condenserMaxBatchSize(int maxBatchSize) {
            this.condenserMaxBatchSize = maxBatchSize;
            return this;
        }
        
        public Builder condenserEnabled(boolean enabled) {
            this.condenserEnabled = enabled;
            return this;
        }
        
        public Builder criteriaEnabled(boolean enabled) {
            this.criteriaEnabled = enabled;
            return this;
        }
        
        public Builder flushIntervalMs(long intervalMs) {
            this.flushIntervalMs = intervalMs;
            return this;
        }
        
        public Builder asyncProcessing(boolean async) {
            this.asyncProcessing = async;
            return this;
        }
        
        public Builder asyncThreadPoolSize(int poolSize) {
            this.asyncThreadPoolSize = poolSize;
            return this;
        }
        
        public Builder preserveMessageOrder(boolean preserveOrder) {
            this.preserveMessageOrder = preserveOrder;
            return this;
        }
        
        public Builder enableMetrics(boolean enable) {
            this.enableMetrics = enable;
            return this;
        }
        
        public ProxyConfiguration build() {
            return new ProxyConfiguration(this);
        }
    }
}
