package com.jmsproxy.examples;

import com.jmsproxy.condenser.JsonFieldExclusionStrategy;
import com.jmsproxy.condenser.JsonMessageCondenser;
import com.jmsproxy.criteria.*;
import com.jmsproxy.core.ProxyConfiguration;
import com.jmsproxy.producer.ProxyMessageProducer;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating the producer proxy with message condensing.
 * Shows both simple wrap() method and advanced builder pattern.
 */
public class ProducerExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ProducerExample.class);
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
            //   MessageProducer producer = session.createProducer(queue);
            // To:
            //   MessageProducer producer = ProxyMessageProducer.wrap(session.createProducer(queue), session);
            
            // Or with condenser settings:
            MessageProducer simpleProducer = ProxyMessageProducer.wrapWithCondenser(
                session.createProducer(queue), session, 2000, 10);  // 2s window, max 10 msgs
            
            // Or with predefined config:
            // MessageProducer producer = ProxyMessageProducer.wrap(
            //     session.createProducer(queue), session, ProxyConfiguration.highThroughput());
            
            logger.info("Simple wrap example created");
            simpleProducer.close();
            
            // ============================================================
            // OPTION 2: Advanced builder pattern (full control)
            // ============================================================
            ProxyMessageProducer proxyProducer = ProxyMessageProducer.builder(session.createProducer(queue), session)
                // Custom comparison strategy for condensing
                .condenser(JsonMessageCondenser.builder()
                    .comparisonStrategy(JsonFieldExclusionStrategy.builder()
                        .excludeField("timestamp")
                        .excludeField("eventId")
                        .excludeField("requestId")
                        .build())
                    .windowMs(2000)
                    .maxBatchSize(10)
                    .build())
                
                // Add send criteria
                .addCriteria(
                    CompositeCriteria.any(
                        PriorityCriteria.atLeast(5),
                        ContentCriteria.isValidJson()
                    )
                )
                
                // Add rate limiting
                .addCriteria(TimeCriteria.rateLimit(100))
                
                .build();
            
            // Send multiple similar messages (will be condensed)
            for (int i = 0; i < 5; i++) {
                String json = String.format(
                    "{\"type\":\"event\",\"data\":\"test\",\"timestamp\":%d,\"value\":42}",
                    System.currentTimeMillis()
                );
                TextMessage message = session.createTextMessage(json);
                proxyProducer.send(message);
                
                logger.info("Sent message {}", i + 1);
                Thread.sleep(100);
            }
            
            // Wait for condenser to flush
            logger.info("Waiting for condenser to flush...");
            Thread.sleep(3000);
            
            // Force flush any remaining
            proxyProducer.flush();
            
            logger.info("Buffered messages remaining: {}", proxyProducer.getBufferedMessageCount());
            
            proxyProducer.close();
        }
    }
}
