package com.jmsproxy.examples;

import com.jmsproxy.consumer.BufferedProxyMessageConsumer;
import com.jmsproxy.consumer.ProxyMessageListener;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating the consumer proxy with message expansion.
 * Shows both simple wrap() method and advanced builder pattern.
 */
public class ConsumerExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerExample.class);
    private static final String BROKER_URL = "tcp://localhost:61616";
    private static final String QUEUE_NAME = "test.proxy.queue";
    
    public static void main(String[] args) throws Exception {
        // Create connection factory
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(BROKER_URL);
        
        try (Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(QUEUE_NAME);
            
            // ============================================================
            // OPTION 1: Simple one-line wrap (easiest migration path)
            // ============================================================
            // Just change:
            //   MessageConsumer consumer = session.createConsumer(queue);
            // To:
            //   MessageConsumer consumer = BufferedProxyMessageConsumer.wrap(session.createConsumer(queue), session);
            
            // Or with custom timestamp field:
            // MessageConsumer consumer = BufferedProxyMessageConsumer.wrap(
            //     session.createConsumer(queue), session, "myTimestampField");
            
            logger.info("Simple wrap() is available for easy migration");
            
            // ============================================================
            // OPTION 2: Advanced builder pattern (full control)
            // ============================================================
            MessageConsumer proxyConsumer = BufferedProxyMessageConsumer
                .builder(session.createConsumer(queue), session)
                .timestampFieldName("timestamp")
                .bufferSize(1000)
                .build();
            
            // Option 2: Use with MessageListener
            AtomicInteger messageCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5);
            
            MessageListener originalListener = message -> {
                try {
                    if (message instanceof TextMessage textMessage) {
                        int count = messageCount.incrementAndGet();
                        logger.info("Received message {}: {}", count, textMessage.getText());
                        latch.countDown();
                    }
                } catch (JMSException e) {
                    logger.error("Error processing message", e);
                }
            };
            
            // Wrap the listener with proxy
            MessageListener proxyListener = ProxyMessageListener.builder(originalListener, session)
                .timestampFieldName("timestamp")
                .build();
            
            proxyConsumer.setMessageListener(proxyListener);
            
            logger.info("Consumer started, waiting for messages...");
            
            // Wait for messages
            if (latch.await(30, TimeUnit.SECONDS)) {
                logger.info("Received all expected messages");
            } else {
                logger.info("Timeout waiting for messages. Received: {}", messageCount.get());
            }
            
            proxyConsumer.close();
        }
    }
}
