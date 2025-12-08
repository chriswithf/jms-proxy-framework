package com.jmsproxy.producer;

import com.jmsproxy.condenser.JsonMessageCondenser;
import com.jmsproxy.core.MessageCondenser;
import com.jmsproxy.core.ProxyConfiguration;
import com.jmsproxy.core.SendCriteria;
import jakarta.jms.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating proxy sessions that automatically wrap producers with proxy functionality.
 */
public class ProxySessionWrapper implements Session {
    
    private final Session delegate;
    private final ProxyConfiguration config;
    private final MessageCondenser condenser;
    private final List<SendCriteria> criteria;
    
    private ProxySessionWrapper(Builder builder) {
        this.delegate = builder.delegate;
        this.config = builder.config;
        this.condenser = builder.condenser;
        this.criteria = new ArrayList<>(builder.criteria);
    }
    
    public static Builder builder(Session session) {
        return new Builder(session);
    }
    
    /**
     * Creates a producer that is automatically wrapped with proxy functionality.
     */
    @Override
    public MessageProducer createProducer(Destination destination) throws JMSException {
        MessageProducer originalProducer = delegate.createProducer(destination);
        
        ProxyMessageProducer.Builder builder = ProxyMessageProducer.builder(originalProducer, delegate)
            .configuration(config);
        
        if (condenser != null) {
            builder.condenser(condenser);
        }
        
        for (SendCriteria criterion : criteria) {
            builder.addCriteria(criterion);
        }
        
        return builder.build();
    }
    
    // Delegate all other Session methods
    
    @Override
    public BytesMessage createBytesMessage() throws JMSException {
        return delegate.createBytesMessage();
    }
    
    @Override
    public MapMessage createMapMessage() throws JMSException {
        return delegate.createMapMessage();
    }
    
    @Override
    public Message createMessage() throws JMSException {
        return delegate.createMessage();
    }
    
    @Override
    public ObjectMessage createObjectMessage() throws JMSException {
        return delegate.createObjectMessage();
    }
    
    @Override
    public ObjectMessage createObjectMessage(java.io.Serializable object) throws JMSException {
        return delegate.createObjectMessage(object);
    }
    
    @Override
    public StreamMessage createStreamMessage() throws JMSException {
        return delegate.createStreamMessage();
    }
    
    @Override
    public TextMessage createTextMessage() throws JMSException {
        return delegate.createTextMessage();
    }
    
    @Override
    public TextMessage createTextMessage(String text) throws JMSException {
        return delegate.createTextMessage(text);
    }
    
    @Override
    public boolean getTransacted() throws JMSException {
        return delegate.getTransacted();
    }
    
    @Override
    public int getAcknowledgeMode() throws JMSException {
        return delegate.getAcknowledgeMode();
    }
    
    @Override
    public void commit() throws JMSException {
        delegate.commit();
    }
    
    @Override
    public void rollback() throws JMSException {
        delegate.rollback();
    }
    
    @Override
    public void close() throws JMSException {
        delegate.close();
    }
    
    @Override
    public void recover() throws JMSException {
        delegate.recover();
    }
    
    @Override
    public MessageListener getMessageListener() throws JMSException {
        return delegate.getMessageListener();
    }
    
    @Override
    public void setMessageListener(MessageListener listener) throws JMSException {
        delegate.setMessageListener(listener);
    }
    
    @Override
    public void run() {
        delegate.run();
    }
    
    @Override
    public MessageConsumer createConsumer(Destination destination) throws JMSException {
        return delegate.createConsumer(destination);
    }
    
    @Override
    public MessageConsumer createConsumer(Destination destination, String messageSelector) throws JMSException {
        return delegate.createConsumer(destination, messageSelector);
    }
    
    @Override
    public MessageConsumer createConsumer(Destination destination, String messageSelector, boolean noLocal) throws JMSException {
        return delegate.createConsumer(destination, messageSelector, noLocal);
    }
    
    @Override
    public MessageConsumer createSharedConsumer(Topic topic, String sharedSubscriptionName) throws JMSException {
        return delegate.createSharedConsumer(topic, sharedSubscriptionName);
    }
    
    @Override
    public MessageConsumer createSharedConsumer(Topic topic, String sharedSubscriptionName, String messageSelector) throws JMSException {
        return delegate.createSharedConsumer(topic, sharedSubscriptionName, messageSelector);
    }
    
    @Override
    public Queue createQueue(String queueName) throws JMSException {
        return delegate.createQueue(queueName);
    }
    
    @Override
    public Topic createTopic(String topicName) throws JMSException {
        return delegate.createTopic(topicName);
    }
    
    @Override
    public TopicSubscriber createDurableSubscriber(Topic topic, String name) throws JMSException {
        return delegate.createDurableSubscriber(topic, name);
    }
    
    @Override
    public TopicSubscriber createDurableSubscriber(Topic topic, String name, String messageSelector, boolean noLocal) throws JMSException {
        return delegate.createDurableSubscriber(topic, name, messageSelector, noLocal);
    }
    
    @Override
    public MessageConsumer createDurableConsumer(Topic topic, String name) throws JMSException {
        return delegate.createDurableConsumer(topic, name);
    }
    
    @Override
    public MessageConsumer createDurableConsumer(Topic topic, String name, String messageSelector, boolean noLocal) throws JMSException {
        return delegate.createDurableConsumer(topic, name, messageSelector, noLocal);
    }
    
    @Override
    public MessageConsumer createSharedDurableConsumer(Topic topic, String name) throws JMSException {
        return delegate.createSharedDurableConsumer(topic, name);
    }
    
    @Override
    public MessageConsumer createSharedDurableConsumer(Topic topic, String name, String messageSelector) throws JMSException {
        return delegate.createSharedDurableConsumer(topic, name, messageSelector);
    }
    
    @Override
    public QueueBrowser createBrowser(Queue queue) throws JMSException {
        return delegate.createBrowser(queue);
    }
    
    @Override
    public QueueBrowser createBrowser(Queue queue, String messageSelector) throws JMSException {
        return delegate.createBrowser(queue, messageSelector);
    }
    
    @Override
    public TemporaryQueue createTemporaryQueue() throws JMSException {
        return delegate.createTemporaryQueue();
    }
    
    @Override
    public TemporaryTopic createTemporaryTopic() throws JMSException {
        return delegate.createTemporaryTopic();
    }
    
    @Override
    public void unsubscribe(String name) throws JMSException {
        delegate.unsubscribe(name);
    }
    
    public static class Builder {
        private final Session delegate;
        private ProxyConfiguration config = ProxyConfiguration.builder().build();
        private MessageCondenser condenser;
        private final List<SendCriteria> criteria = new ArrayList<>();
        
        Builder(Session delegate) {
            this.delegate = delegate;
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
        
        public Builder addCriteria(SendCriteria criterion) {
            this.criteria.add(criterion);
            return this;
        }
        
        public ProxySessionWrapper build() {
            return new ProxySessionWrapper(this);
        }
    }
}
