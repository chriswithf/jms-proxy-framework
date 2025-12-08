package com.jmsproxy.examples;

import com.jmsproxy.criteria.*;
import com.jmsproxy.core.SendCriteria;
import com.jmsproxy.producer.ProxyMessageProducer;
import com.jmsproxy.producer.ProxySessionWrapper;
import jakarta.jms.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating the various SendCriteria options.
 */
public class CriteriaExample {
    
    private static final Logger logger = LoggerFactory.getLogger(CriteriaExample.class);
    
    public static void main(String[] args) throws Exception {
        // Start embedded Artemis broker
        EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
        broker.setConfiguration(new ConfigurationImpl()
            .setPersistenceEnabled(false)
            .setJournalDirectory("target/data/journal")
            .setSecurityEnabled(false)
            .addAcceptorConfiguration("tcp", "tcp://localhost:61616"));
        broker.start();
        
        try {
            demonstrateCriteria();
        } finally {
            broker.stop();
        }
    }
    
    private static void demonstrateCriteria() throws Exception {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://localhost:61616");
        
        try (Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("criteria.test.queue");
            
            // 1. Priority-based criteria
            logger.info("=== Priority Criteria ===");
            SendCriteria highPriority = PriorityCriteria.atLeast(7);
            SendCriteria mediumPriority = PriorityCriteria.range(4, 6);
            
            // 2. Property-based criteria
            logger.info("=== Property Criteria ===");
            SendCriteria hasCustomerId = PropertyCriteria.exists("customerId");
            SendCriteria isVipCustomer = PropertyCriteria.matches("customerType", "VIP|PREMIUM");
            SendCriteria regionEurope = PropertyCriteria.in("region", "EU", "UK", "CH");
            
            // 3. Content-based criteria
            logger.info("=== Content Criteria ===");
            SendCriteria validJson = ContentCriteria.isValidJson();
            SendCriteria hasOrderId = ContentCriteria.jsonFieldExists("orderId");
            SendCriteria isUrgent = ContentCriteria.jsonFieldEquals("priority", "URGENT");
            SendCriteria reasonableSize = ContentCriteria.maxLength(10000);
            
            // 4. Time-based criteria
            logger.info("=== Time Criteria ===");
            SendCriteria businessHoursOnly = TimeCriteria.businessHours();
            SendCriteria rateLimited = TimeCriteria.rateLimit(10); // 10 per second
            SendCriteria throttled = TimeCriteria.throttle(100); // Min 100ms between messages
            
            // 5. Destination-based criteria
            logger.info("=== Destination Criteria ===");
            SendCriteria toProductionQueues = DestinationCriteria.nameStartsWith("prod.");
            SendCriteria excludeTestQueues = DestinationCriteria.exclude("test.queue", "dev.queue");
            
            // 6. Composite criteria - combining multiple criteria
            logger.info("=== Composite Criteria ===");
            
            // All conditions must be met
            SendCriteria strictCriteria = CompositeCriteria.all(
                validJson,
                hasCustomerId,
                reasonableSize
            );
            
            // Any condition is sufficient
            SendCriteria flexibleCriteria = CompositeCriteria.any(
                highPriority,
                isUrgent,
                isVipCustomer
            );
            
            // Complex nested criteria using fluent API
            SendCriteria complexCriteria = validJson
                .and(hasOrderId.or(hasCustomerId))
                .and(businessHoursOnly.or(highPriority))
                .and(rateLimited);
            
            // 7. Custom criteria using lambda
            logger.info("=== Custom Criteria ===");
            SendCriteria customCriteria = message -> {
                try {
                    // Custom logic: only allow messages with correlation ID starting with "TXN-"
                    String correlationId = message.getJMSCorrelationID();
                    return correlationId != null && correlationId.startsWith("TXN-");
                } catch (JMSException e) {
                    return false;
                }
            };
            
            // Create producer with multiple criteria
            MessageProducer originalProducer = session.createProducer(queue);
            ProxyMessageProducer proxyProducer = ProxyMessageProducer.builder(originalProducer, session)
                .disableCondenser() // Focus on criteria only
                .addCriteria(
                    validJson,
                    reasonableSize,
                    rateLimited
                )
                .build();
            
            // Test messages
            logger.info("Sending test messages...");
            
            // This should pass (valid JSON)
            TextMessage validMessage = session.createTextMessage("{\"orderId\":\"123\",\"amount\":99.99}");
            proxyProducer.send(validMessage);
            logger.info("Valid JSON message: SENT");
            
            // This should fail (not JSON)
            TextMessage invalidMessage = session.createTextMessage("This is not JSON");
            proxyProducer.send(invalidMessage);
            logger.info("Invalid JSON message: BLOCKED by criteria");
            
            // Using ProxySessionWrapper for automatic wrapping
            logger.info("=== Using ProxySessionWrapper ===");
            Session proxySession = ProxySessionWrapper.builder(session)
                .addCriteria(validJson)
                .addCriteria(ContentCriteria.minLength(10))
                .build();
            
            // Producer created from proxy session is automatically wrapped
            MessageProducer autoProxiedProducer = proxySession.createProducer(queue);
            autoProxiedProducer.send(session.createTextMessage("{\"auto\":\"proxied\"}"));
            logger.info("Auto-proxied message sent");
            
            proxyProducer.close();
            autoProxiedProducer.close();
        }
    }
}
