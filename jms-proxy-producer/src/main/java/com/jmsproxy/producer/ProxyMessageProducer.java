package com.jmsproxy.producer;

import com.jmsproxy.condenser.JsonMessageCondenser;
import com.jmsproxy.core.*;
import jakarta.jms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Proxy MessageProducer that transparently intercepts message sending.
 * Provides message condensing and criteria-based filtering.
 */
public class ProxyMessageProducer implements MessageProducer {
    
    private static final Logger logger = LoggerFactory.getLogger(ProxyMessageProducer.class);
    
    private final MessageProducer delegate;
    private final Session session;
    private final ProxyConfiguration config;
    private final MessageCondenser condenser;
    private final List<SendCriteria> criteria;
    private final ScheduledExecutorService flushExecutor;
    private final Object lock = new Object();
    
    private int deliveryMode = DeliveryMode.PERSISTENT;
    private int priority = Message.DEFAULT_PRIORITY;
    private long timeToLive = Message.DEFAULT_TIME_TO_LIVE;
    private long deliveryDelay = Message.DEFAULT_DELIVERY_DELAY;
    private boolean disableMessageID = false;
    private boolean disableMessageTimestamp = false;
    
    // Adaptive flush scheduling - avoid polling when idle
    private volatile boolean flushScheduled = false;
    
    private ProxyMessageProducer(Builder builder) {
        this.delegate = builder.delegate;
        this.session = builder.session;
        this.config = builder.config;
        this.condenser = builder.condenser;
        this.criteria = new ArrayList<>(builder.criteria);
        
        // Start background flush if condenser is enabled
        // Use adaptive scheduling instead of fixed rate polling
        if (config.isCondenserEnabled() && condenser != null) {
            this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "JMSProxy-FlushExecutor");
                t.setDaemon(true);
                return t;
            });
            // Don't start polling immediately - will be triggered on first message
        } else {
            this.flushExecutor = null;
        }
        
        logger.info("ProxyMessageProducer created with condenser={}, criteria={}",
            config.isCondenserEnabled(), criteria.size());
    }
    
    public static Builder builder(MessageProducer delegate, Session session) {
        return new Builder(delegate, session);
    }
    
    /**
     * Wraps an existing MessageProducer with default settings.
     * This is the simplest way to add proxy capabilities - just replace:
     * <pre>
     *   MessageProducer producer = session.createProducer(queue);
     * </pre>
     * with:
     * <pre>
     *   MessageProducer producer = ProxyMessageProducer.wrap(session.createProducer(queue), session);
     * </pre>
     */
    public static MessageProducer wrap(MessageProducer producer, Session session) {
        return builder(producer, session).build();
    }
    
    /**
     * Wraps an existing MessageProducer with custom configuration.
     */
    public static MessageProducer wrap(MessageProducer producer, Session session, ProxyConfiguration config) {
        return builder(producer, session).configuration(config).build();
    }
    
    /**
     * Wraps an existing MessageProducer with JSON condensing using specified parameters.
     * @param windowMs Time window for condensing similar messages
     * @param maxBatchSize Maximum messages per condensed batch
     */
    public static MessageProducer wrapWithCondenser(MessageProducer producer, Session session, 
                                                     long windowMs, int maxBatchSize) {
        return builder(producer, session)
            .withJsonCondenser(windowMs, maxBatchSize)
            .build();
    }
    
    /**
     * Wraps an existing MessageProducer in pass-through mode (no condensing, no criteria).
     * Useful for debugging or when you want to selectively disable proxy features.
     */
    public static MessageProducer wrapPassThrough(MessageProducer producer, Session session) {
        return builder(producer, session)
            .configuration(ProxyConfiguration.passThrough())
            .build();
    }

    public Map<String, Long> getCondenserStats() {
        if (condenser != null) {
            return condenser.getStats();
        }
        return java.util.Collections.emptyMap();
    }
    
    @Override
    public void send(Message message) throws JMSException {
        send(getDestination(), message);
    }
    
    @Override
    public void send(Message message, int deliveryMode, int priority, long timeToLive) throws JMSException {
        send(getDestination(), message, deliveryMode, priority, timeToLive);
    }
    
    @Override
    public void send(Destination destination, Message message) throws JMSException {
        send(destination, message, this.deliveryMode, this.priority, this.timeToLive);
    }
    
    @Override
    public void send(Destination destination, Message message, int deliveryMode, int priority, long timeToLive) 
            throws JMSException {
        
        // Fast path: Check criteria first (lock-free for simple property checks)
        if (config.isCriteriaEnabled() && !evaluateCriteria(message)) {
            logger.debug("Message blocked by criteria");
            return;
        }
        
        // Check if condensing applies
        if (config.isCondenserEnabled() && condenser != null && condenser.shouldCondense(message)) {
            // Only synchronize for condenser operations
            synchronized (lock) {
                condenser.addMessage(message);
                scheduleFlushIfNeeded();
            }
            // Guard debug logging - avoid string formatting when not needed
            if (logger.isDebugEnabled()) {
                logger.debug("Message added to condenser buffer");
            }
            return;
        }
        
        // Send directly - no lock needed for direct sends
        // Check if we should use the default destination to avoid UnsupportedOperationException
        Destination defaultDest = null;
        try {
            defaultDest = delegate.getDestination();
        } catch (JMSException e) {
            // Ignore if we can't get destination
        }

        if (defaultDest != null && (destination == null || destination.equals(defaultDest))) {
             delegate.send(message, deliveryMode, priority, timeToLive);
        } else {
             delegate.send(destination, message, deliveryMode, priority, timeToLive);
        }
    }
    
    /**
     * Schedule a flush if not already scheduled - adaptive scheduling saves CPU when idle.
     */
    private void scheduleFlushIfNeeded() {
        if (!flushScheduled && flushExecutor != null) {
            flushScheduled = true;
            flushExecutor.schedule(() -> {
                flushCondenser();
                flushScheduled = false;
                // Re-schedule if there are still messages
                if (condenser != null && condenser.hasMessagesToFlush()) {
                    scheduleFlushIfNeeded();
                }
            }, config.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
        }
    }
    
    @Override
    public void send(Message message, CompletionListener completionListener) throws JMSException {
        send(getDestination(), message, completionListener);
    }
    
    @Override
    public void send(Message message, int deliveryMode, int priority, long timeToLive, 
                     CompletionListener completionListener) throws JMSException {
        send(getDestination(), message, deliveryMode, priority, timeToLive, completionListener);
    }
    
    @Override
    public void send(Destination destination, Message message, CompletionListener completionListener) 
            throws JMSException {
        send(destination, message, this.deliveryMode, this.priority, this.timeToLive, completionListener);
    }
    
    @Override
    public void send(Destination destination, Message message, int deliveryMode, int priority, 
                     long timeToLive, CompletionListener completionListener) throws JMSException {
        
        // Fast path: Check criteria first (lock-free)
        if (config.isCriteriaEnabled() && !evaluateCriteria(message)) {
            logger.debug("Message blocked by criteria");
            completionListener.onCompletion(message);
            return;
        }
        
        // Check if condensing applies
        if (config.isCondenserEnabled() && condenser != null && condenser.shouldCondense(message)) {
            synchronized (lock) {
                condenser.addMessage(message);
                scheduleFlushIfNeeded();
            }
            completionListener.onCompletion(message);
            return;
        }
        
        delegate.send(destination, message, deliveryMode, priority, timeToLive, completionListener);
    }
    
    private boolean evaluateCriteria(Message message) {
        for (SendCriteria criterion : criteria) {
            if (!criterion.evaluate(message)) {
                return false;
            }
        }
        return true;
    }
    
    private void flushCondenser() {
        List<CondensedMessageEnvelope> envelopes = null;
        
        synchronized (lock) {
            if (condenser == null || !condenser.hasMessagesToFlush()) {
                return;
            }
            
            try {
                // Flush returns lazy envelopes - fast operation inside lock
                envelopes = condenser.flush();
            } catch (Exception e) {
                logger.error("Failed to flush condenser buffer", e);
            }
        }
        
        // Process envelopes OUTSIDE the lock to avoid blocking send() threads
        // The heavy JSON serialization happens here when getAggregatedContent() is called
        if (envelopes != null) {
            for (CondensedMessageEnvelope envelope : envelopes) {
                try {
                    TextMessage condensedMessage = session.createTextMessage(envelope.getAggregatedContent());
                    
                    // Set condensed marker properties
                    condensedMessage.setBooleanProperty(CondensedMessageEnvelope.CONDENSED_MARKER, true);
                    condensedMessage.setIntProperty(CondensedMessageEnvelope.CONDENSED_COUNT, envelope.getMessageCount());
                    condensedMessage.setLongProperty(CondensedMessageEnvelope.CONDENSED_TIMESTAMPS, envelope.getFirstTimestamp());
                    
                    delegate.send(condensedMessage, deliveryMode, priority, timeToLive);
                    
                    // Reduce log level to debug - info logging in hot path wastes CPU
                    if (logger.isDebugEnabled()) {
                        logger.debug("Sent condensed message containing {} original messages", envelope.getMessageCount());
                    }
                } catch (JMSException e) {
                    logger.error("Failed to send condensed message", e);
                }
            }
        }
    }
    
    /**
     * Forces immediate flush of all buffered messages.
     */
    public void flush() throws JMSException {
        List<CondensedMessageEnvelope> envelopes = null;
        synchronized (lock) {
            if (condenser != null) {
                envelopes = condenser.flush();
            }
        }
        
        if (envelopes != null) {
            for (CondensedMessageEnvelope envelope : envelopes) {
                TextMessage condensedMessage = session.createTextMessage(envelope.getAggregatedContent());
                condensedMessage.setBooleanProperty(CondensedMessageEnvelope.CONDENSED_MARKER, true);
                condensedMessage.setIntProperty(CondensedMessageEnvelope.CONDENSED_COUNT, envelope.getMessageCount());
                delegate.send(condensedMessage);
            }
        }
    }
    
    /**
     * Returns the number of messages currently buffered in the condenser.
     */
    public int getBufferedMessageCount() {
        return condenser != null ? condenser.getBufferedMessageCount() : 0;
    }
    
    @Override
    public void close() throws JMSException {
        // Flush remaining messages before closing
        try {
            flush();
        } catch (JMSException e) {
            logger.warn("Failed to flush messages on close", e);
        }
        
        // Shutdown flush executor
        if (flushExecutor != null) {
            flushExecutor.shutdown();
            try {
                if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    flushExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                flushExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        delegate.close();
    }
    
    @Override
    public Destination getDestination() throws JMSException {
        return delegate.getDestination();
    }
    
    @Override
    public void setDeliveryMode(int deliveryMode) throws JMSException {
        this.deliveryMode = deliveryMode;
        delegate.setDeliveryMode(deliveryMode);
    }
    
    @Override
    public int getDeliveryMode() throws JMSException {
        return delegate.getDeliveryMode();
    }
    
    @Override
    public void setPriority(int priority) throws JMSException {
        this.priority = priority;
        delegate.setPriority(priority);
    }
    
    @Override
    public int getPriority() throws JMSException {
        return delegate.getPriority();
    }
    
    @Override
    public void setTimeToLive(long timeToLive) throws JMSException {
        this.timeToLive = timeToLive;
        delegate.setTimeToLive(timeToLive);
    }
    
    @Override
    public long getTimeToLive() throws JMSException {
        return delegate.getTimeToLive();
    }
    
    @Override
    public void setDeliveryDelay(long deliveryDelay) throws JMSException {
        this.deliveryDelay = deliveryDelay;
        delegate.setDeliveryDelay(deliveryDelay);
    }
    
    @Override
    public long getDeliveryDelay() throws JMSException {
        return delegate.getDeliveryDelay();
    }
    
    @Override
    public void setDisableMessageID(boolean value) throws JMSException {
        this.disableMessageID = value;
        delegate.setDisableMessageID(value);
    }
    
    @Override
    public boolean getDisableMessageID() throws JMSException {
        return delegate.getDisableMessageID();
    }
    
    @Override
    public void setDisableMessageTimestamp(boolean value) throws JMSException {
        this.disableMessageTimestamp = value;
        delegate.setDisableMessageTimestamp(value);
    }
    
    @Override
    public boolean getDisableMessageTimestamp() throws JMSException {
        return delegate.getDisableMessageTimestamp();
    }
    
    /**
     * Builder for ProxyMessageProducer.
     */
    public static class Builder {
        private final MessageProducer delegate;
        private final Session session;
        private ProxyConfiguration config = ProxyConfiguration.builder().build();
        private MessageCondenser condenser;
        private final List<SendCriteria> criteria = new ArrayList<>();
        
        Builder(MessageProducer delegate, Session session) {
            this.delegate = delegate;
            this.session = session;
        }
        
        public Builder configuration(ProxyConfiguration config) {
            this.config = config;
            return this;
        }
        
        public Builder condenser(MessageCondenser condenser) {
            this.condenser = condenser;
            return this;
        }
        
        public Builder withJsonCondenser() {
            this.condenser = JsonMessageCondenser.builder().build();
            return this;
        }
        
        public Builder withJsonCondenser(long windowMs, int maxBatchSize) {
            this.condenser = JsonMessageCondenser.builder()
                .windowMs(windowMs)
                .maxBatchSize(maxBatchSize)
                .build();
            return this;
        }
        
        public Builder addCriteria(SendCriteria criterion) {
            this.criteria.add(criterion);
            return this;
        }
        
        public Builder addCriteria(SendCriteria... criteria) {
            for (SendCriteria c : criteria) {
                this.criteria.add(c);
            }
            return this;
        }
        
        public Builder condenserWindowMs(long windowMs) {
            this.config = ProxyConfiguration.builder()
                .condenserWindowMs(windowMs)
                .condenserMaxBatchSize(config.getCondenserMaxBatchSize())
                .condenserEnabled(config.isCondenserEnabled())
                .criteriaEnabled(config.isCriteriaEnabled())
                .flushIntervalMs(config.getFlushIntervalMs())
                .build();
            return this;
        }
        
        public Builder condenserMaxBatchSize(int maxBatchSize) {
            this.config = ProxyConfiguration.builder()
                .condenserWindowMs(config.getCondenserWindowMs())
                .condenserMaxBatchSize(maxBatchSize)
                .condenserEnabled(config.isCondenserEnabled())
                .criteriaEnabled(config.isCriteriaEnabled())
                .flushIntervalMs(config.getFlushIntervalMs())
                .build();
            return this;
        }
        
        public Builder disableCondenser() {
            this.config = ProxyConfiguration.builder()
                .condenserWindowMs(config.getCondenserWindowMs())
                .condenserMaxBatchSize(config.getCondenserMaxBatchSize())
                .condenserEnabled(false)
                .criteriaEnabled(config.isCriteriaEnabled())
                .flushIntervalMs(config.getFlushIntervalMs())
                .build();
            return this;
        }
        
        public Builder disableCriteria() {
            this.config = ProxyConfiguration.builder()
                .condenserWindowMs(config.getCondenserWindowMs())
                .condenserMaxBatchSize(config.getCondenserMaxBatchSize())
                .condenserEnabled(config.isCondenserEnabled())
                .criteriaEnabled(false)
                .flushIntervalMs(config.getFlushIntervalMs())
                .build();
            return this;
        }
        
        public ProxyMessageProducer build() {
            // Create default condenser if enabled but not set
            if (config.isCondenserEnabled() && condenser == null) {
                condenser = JsonMessageCondenser.builder()
                    .windowMs(config.getCondenserWindowMs())
                    .maxBatchSize(config.getCondenserMaxBatchSize())
                    .build();
            }
            return new ProxyMessageProducer(this);
        }
    }
}
