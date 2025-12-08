package com.jmsproxy.consumer;

import com.jmsproxy.condenser.JsonMessageExpander;
import com.jmsproxy.core.MessageExpander;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Proxy MessageListener that transparently expands condensed messages.
 * Can be used as a drop-in replacement for any MessageListener.
 */
public class ProxyMessageListener implements MessageListener {
    
    private static final Logger logger = LoggerFactory.getLogger(ProxyMessageListener.class);
    
    private final MessageListener delegate;
    private final MessageExpander expander;
    
    private ProxyMessageListener(Builder builder) {
        this.delegate = builder.delegate;
        this.expander = builder.expander;
    }
    
    public static Builder builder(MessageListener delegate, Session session) {
        return new Builder(delegate, session);
    }
    
    /**
     * Wraps an existing MessageListener with default settings.
     */
    public static MessageListener wrap(MessageListener listener, Session session) {
        return builder(listener, session).build();
    }
    
    @Override
    public void onMessage(Message message) {
        try {
            if (expander.isCondensed(message)) {
                List<Message> expandedMessages = expander.expand(message);
                logger.debug("Expanding condensed message to {} individual messages", expandedMessages.size());
                
                for (Message expandedMessage : expandedMessages) {
                    delegate.onMessage(expandedMessage);
                }
            } else {
                delegate.onMessage(message);
            }
        } catch (Exception e) {
            logger.error("Error processing message in proxy listener", e);
            // Still forward the original message on error
            delegate.onMessage(message);
        }
    }
    
    /**
     * Builder for ProxyMessageListener.
     */
    public static class Builder {
        private final MessageListener delegate;
        private final Session session;
        private MessageExpander expander;
        private String timestampFieldName = "timestamp";
        
        Builder(MessageListener delegate, Session session) {
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
        
        public ProxyMessageListener build() {
            if (expander == null) {
                expander = new JsonMessageExpander(session, timestampFieldName);
            }
            return new ProxyMessageListener(this);
        }
    }
}
