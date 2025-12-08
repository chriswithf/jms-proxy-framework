package com.jmsproxy.examples;

import com.jmsproxy.condenser.JsonFieldExclusionStrategy;
import com.jmsproxy.condenser.JsonMessageCondenser;
import com.jmsproxy.consumer.BufferedProxyMessageConsumer;
import com.jmsproxy.criteria.*;
import com.jmsproxy.producer.ProxyMessageProducer;
import jakarta.jms.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Complete example demonstrating the full JMS Proxy Framework with embedded Artemis.
 * Shows producer condensing and consumer expansion working together.
 */
public class FullExample {
    
    private static final Logger logger = LoggerFactory.getLogger(FullExample.class);
    private static final String QUEUE_NAME = "test.condensed.queue";
    
    public static void main(String[] args) throws Exception {
        // Start embedded Artemis broker
        EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
        broker.setConfiguration(new ConfigurationImpl()
            .setPersistenceEnabled(false)
            .setJournalDirectory("target/data/journal")
            .setSecurityEnabled(false)
            .addAcceptorConfiguration("tcp", "tcp://localhost:61616"));
        broker.start();
        
        logger.info("Embedded Artemis broker started");
        
        try {
            runExample();
        } finally {
            broker.stop();
            logger.info("Broker stopped");
        }
    }
    
    private static void runExample() throws Exception {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://localhost:61616");
        
        // Producer connection
        Connection producerConnection = factory.createConnection();
        producerConnection.start();
        Session producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = producerSession.createQueue(QUEUE_NAME);
        
        // Consumer connection
        Connection consumerConnection = factory.createConnection();
        consumerConnection.start();
        Session consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        
        // Setup consumer first
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(10); // Expect 10 individual messages
        
        MessageConsumer originalConsumer = consumerSession.createConsumer(queue);
        BufferedProxyMessageConsumer proxyConsumer = BufferedProxyMessageConsumer
            .builder(originalConsumer, consumerSession)
            .timestampFieldName("timestamp")
            .build();
        
        proxyConsumer.setMessageListener(message -> {
            try {
                if (message instanceof TextMessage textMessage) {
                    int count = receivedCount.incrementAndGet();
                    logger.info("Received expanded message {}: {}", count, 
                        textMessage.getText().substring(0, Math.min(100, textMessage.getText().length())));
                    latch.countDown();
                }
            } catch (JMSException e) {
                logger.error("Error processing message", e);
            }
        });
        
        logger.info("Consumer ready, starting producer...");
        
        // Setup producer with condensing
        MessageProducer originalProducer = producerSession.createProducer(queue);
        ProxyMessageProducer proxyProducer = ProxyMessageProducer.builder(originalProducer, producerSession)
            .condenser(JsonMessageCondenser.builder()
                .comparisonStrategy(JsonFieldExclusionStrategy.builder()
                    .excludeField("timestamp")
                    .excludeField("eventId")
                    .build())
                .windowMs(1000) // 1 second window
                .maxBatchSize(5) // Batch of 5 messages
                .build())
            .addCriteria(ContentCriteria.isValidJson())
            .build();
        
        // Send 10 similar messages (should be condensed into 2 batches of 5)
        logger.info("Sending 10 similar JSON messages...");
        for (int i = 0; i < 10; i++) {
            String json = String.format(
                "{\"type\":\"sensor\",\"sensorId\":\"S001\",\"value\":%.2f,\"unit\":\"celsius\",\"timestamp\":%d,\"eventId\":\"%d\"}",
                22.5, // Same temperature
                System.currentTimeMillis(),
                i
            );
            TextMessage message = producerSession.createTextMessage(json);
            proxyProducer.send(message);
            logger.info("Queued message {}", i + 1);
            Thread.sleep(50);
        }
        
        logger.info("All messages sent to condenser. Buffered: {}", proxyProducer.getBufferedMessageCount());
        
        // Wait for condenser to flush
        Thread.sleep(2000);
        proxyProducer.flush();
        
        // Wait for consumer to receive all expanded messages
        logger.info("Waiting for consumer to receive all expanded messages...");
        if (latch.await(10, TimeUnit.SECONDS)) {
            logger.info("SUCCESS: All {} messages received and expanded correctly!", receivedCount.get());
        } else {
            logger.warn("Timeout: Only received {} of 10 expected messages", receivedCount.get());
        }
        
        // Cleanup
        proxyProducer.close();
        proxyConsumer.close();
        producerSession.close();
        consumerSession.close();
        producerConnection.close();
        consumerConnection.close();
    }
}
