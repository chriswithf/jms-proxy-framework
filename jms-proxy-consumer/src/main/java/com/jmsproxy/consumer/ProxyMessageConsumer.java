package com.jmsproxy.consumer;

import com.jmsproxy.condenser.JsonMessageExpander;
import com.jmsproxy.core.MessageExpander;
import jakarta.jms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Proxy MessageConsumer that transparently expands condensed messages.
 */
public class ProxyMessageConsumer implements MessageConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(ProxyMessageConsumer.class);
    
    private final MessageConsumer delegate;
    private final MessageExpander expander;
    private final Session session;
    private MessageListener originalListener;
    
    private ProxyMessageConsumer(Builder builder) {
        this.delegate = builder.delegate;
        this.expander = builder.expander;
        this.session = builder.session;
    }
    
    public static Builder builder(MessageConsumer delegate, Session session) {
        return new Builder(delegate, session);
    }
    
    /**
     * Wraps an existing MessageConsumer with default settings.
     */
    public static MessageConsumer wrap(MessageConsumer consumer, Session session) {
        return builder(consumer, session).build();
    }
    
    @Override
    public Message receive() throws JMSException {
        Message message = delegate.receive();
        return expandIfNeeded(message);
    }
    
    @Override
    public Message receive(long timeout) throws JMSException {
        Message message = delegate.receive(timeout);
        return expandIfNeeded(message);
    }
    
    @Override
    public Message receiveNoWait() throws JMSException {
        Message message = delegate.receiveNoWait();
        return expandIfNeeded(message);
    }
    
    private Message expandIfNeeded(Message message) throws JMSException {
        if (message == null) {
            return null;
        }
        
        if (expander.isCondensed(message)) {
            List<Message> expanded = expander.expand(message);
            if (!expanded.isEmpty()) {
                // Return first message, queue others for subsequent receives
                // Note: For full implementation, would need a local queue
                logger.debug("Expanded condensed message, returning first of {} messages", expanded.size());
                return expanded.get(0);
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
        
        // Wrap listener to expand messages
        MessageListener expandingListener = message -> {
            try {
                if (expander.isCondensed(message)) {
                    List<Message> expanded = expander.expand(message);
                    logger.debug("Expanding condensed message to {} individual messages", expanded.size());
                    
                    for (Message expandedMessage : expanded) {
                        listener.onMessage(expandedMessage);
                    }
                } else {
                    listener.onMessage(message);
                }
            } catch (Exception e) {
                logger.error("Error processing message", e);
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
        delegate.close();
    }
    
    /**
     * Builder for ProxyMessageConsumer.
     */
    public static class Builder {
        private final MessageConsumer delegate;
        private final Session session;
        private MessageExpander expander;
        private String timestampFieldName = "timestamp";
        
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
        
        public ProxyMessageConsumer build() {
            if (expander == null) {
                expander = new JsonMessageExpander(session, timestampFieldName);
            }
            return new ProxyMessageConsumer(this);
        }
    }
}
