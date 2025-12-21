package com.jmsproxy.consumer;

import com.jmsproxy.condenser.JsonMessageExpander;
import com.jmsproxy.core.MessageExpander;
import jakarta.jms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Advanced proxy consumer that properly queues expanded messages for sequential delivery.
 * This ensures that all expanded messages are delivered even with synchronous receive().
 */
public class BufferedProxyMessageConsumer implements MessageConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(BufferedProxyMessageConsumer.class);
    
    private final MessageConsumer delegate;
    private final MessageExpander expander;
    private final Session session;
    private final BlockingQueue<Message> expandedMessageQueue;
    private MessageListener originalListener;
    
    private BufferedProxyMessageConsumer(Builder builder) {
        this.delegate = builder.delegate;
        this.expander = builder.expander;
        this.session = builder.session;
        this.expandedMessageQueue = new LinkedBlockingQueue<>(builder.bufferSize);
    }
    
    public static Builder builder(MessageConsumer delegate, Session session) {
        return new Builder(delegate, session);
    }
    
    /**
     * Wraps an existing MessageConsumer with default settings.
     * This is the simplest way to add proxy capabilities - just replace:
     * <pre>
     *   MessageConsumer consumer = session.createConsumer(queue);
     * </pre>
     * with:
     * <pre>
     *   MessageConsumer consumer = BufferedProxyMessageConsumer.wrap(session.createConsumer(queue), session);
     * </pre>
     */
    public static MessageConsumer wrap(MessageConsumer consumer, Session session) {
        return builder(consumer, session).build();
    }
    
    /**
     * Wraps an existing MessageConsumer with a custom timestamp field name.
     * @param timestampFieldName The JSON field name used for timestamps in condensed messages
     */
    public static MessageConsumer wrap(MessageConsumer consumer, Session session, String timestampFieldName) {
        return builder(consumer, session)
            .timestampFieldName(timestampFieldName)
            .build();
    }
    
    /**
     * Wraps an existing MessageConsumer with custom buffer size.
     * @param bufferSize Maximum number of expanded messages to buffer
     */
    public static MessageConsumer wrapWithBuffer(MessageConsumer consumer, Session session, int bufferSize) {
        return builder(consumer, session)
            .bufferSize(bufferSize)
            .build();
    }
    
    @Override
    public Message receive() throws JMSException {
        // First check if there are queued expanded messages
        Message queued = expandedMessageQueue.poll();
        if (queued != null) {
            return queued;
        }
        
        // Receive from delegate and potentially expand
        Message message = delegate.receive();
        return processAndQueueRemaining(message);
    }
    
    @Override
    public Message receive(long timeout) throws JMSException {
        // First check if there are queued expanded messages
        Message queued = expandedMessageQueue.poll();
        if (queued != null) {
            return queued;
        }
        
        // Receive from delegate with timeout
        Message message = delegate.receive(timeout);
        return processAndQueueRemaining(message);
    }
    
    @Override
    public Message receiveNoWait() throws JMSException {
        // First check if there are queued expanded messages
        Message queued = expandedMessageQueue.poll();
        if (queued != null) {
            return queued;
        }
        
        // Receive from delegate without waiting
        Message message = delegate.receiveNoWait();
        return processAndQueueRemaining(message);
    }
    
    private Message processAndQueueRemaining(Message message) {
        if (message == null) {
            return null;
        }
        
        if (expander.isCondensed(message)) {
            List<Message> expanded = expander.expand(message);
            if (!expanded.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Expanded condensed message into {} individual messages", expanded.size());
                }
                
                // Return first, queue the rest
                Message first = expanded.get(0);
                for (int i = 1; i < expanded.size(); i++) {
                    if (!expandedMessageQueue.offer(expanded.get(i))) {
                        logger.warn("Expanded message queue full, dropping message");
                    }
                }
                return first;
            }
        }
        
        return message;
    }
    
    @Override
    public void setMessageListener(MessageListener listener) throws JMSException {
        this.originalListener = listener;
        
        if (listener == null) {
            delegate.setMessageListener(null);
            return;
        }
        
        // Create expanding listener
        MessageListener expandingListener = message -> {
            try {
                if (expander.isCondensed(message)) {
                    List<Message> expanded = expander.expand(message);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Expanding condensed message to {} individual messages for listener", 
                            expanded.size());
                    }
                    
                    for (Message expandedMessage : expanded) {
                        listener.onMessage(expandedMessage);
                    }
                } else {
                    listener.onMessage(message);
                }
            } catch (Exception e) {
                logger.error("Error in expanding message listener", e);
                // Forward original message on error
                listener.onMessage(message);
            }
        };
        
        delegate.setMessageListener(expandingListener);
    }
    
    @Override
    public MessageListener getMessageListener() throws JMSException {
        return originalListener;
    }
    
    @Override
    public String getMessageSelector() throws JMSException {
        return delegate.getMessageSelector();
    }
    
    @Override
    public void close() throws JMSException {
        expandedMessageQueue.clear();
        delegate.close();
    }
    
    /**
     * Returns the number of expanded messages waiting in the queue.
     */
    public int getQueuedMessageCount() {
        return expandedMessageQueue.size();
    }
    
    /**
     * Builder for BufferedProxyMessageConsumer.
     */
    public static class Builder {
        private final MessageConsumer delegate;
        private final Session session;
        private MessageExpander expander;
        private String timestampFieldName = "timestamp";
        private int bufferSize = 1000;
        
        Builder(MessageConsumer delegate, Session session) {
            this.delegate = delegate;
            this.session = session;
        }
        
        public Builder expander(MessageExpander expander) {
            this.expander = expander;
            return this;
        }
        
        public Builder timestampFieldName(String fieldName) {
            this.timestampFieldName = fieldName;
            return this;
        }
        
        public Builder bufferSize(int size) {
            this.bufferSize = size;
            return this;
        }
        
        public BufferedProxyMessageConsumer build() {
            if (expander == null) {
                expander = new JsonMessageExpander(session, timestampFieldName);
            }
            return new BufferedProxyMessageConsumer(this);
        }
    }
}
