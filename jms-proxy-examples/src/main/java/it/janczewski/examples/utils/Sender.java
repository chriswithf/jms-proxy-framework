package it.janczewski.examples.utils;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jmsproxy.producer.ProxyMessageProducer;
import com.jmsproxy.criteria.PropertyCriteria;

import jakarta.jms.*;
import java.math.BigInteger;
import java.security.SecureRandom;

public class Sender {
    private final Logger logger = LoggerFactory.getLogger(Sender.class);
    private SecureRandom random = new SecureRandom();

    public void createTask(){
        String taskName = generateTaskName();
        Runnable sendTask = () -> {
            // Use Artemis ConnectionFactory as per the project dependencies (ActiveMQ Classic vs Artemis)
            // The original example used ActiveMQ 5.x client, but our project uses Artemis client.
            // We'll stick to Artemis factory but keep the structure.
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                System.getenv().getOrDefault("BROKER_URL", "tcp://broker:61616")
            );
            Connection connection = null;
            try {
                connection = connectionFactory.createConnection();
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Destination destination = session.createQueue("TJ_Test");
                
                // Use anonymous producer to avoid Artemis client restriction when wrapping
                // Directly wrap the producer with ProxyMessageProducer and add criteria
                MessageProducer producer = ProxyMessageProducer.builder(session.createProducer(null), session)
                    .addCriteria(PropertyCriteria.equals("isValid", "true"))
                    .build();
                producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

                String text = "Hello from: " + taskName + " : " + this.hashCode();
                TextMessage message = session.createTextMessage(text);
                // Set the property to satisfy the criteria
                message.setStringProperty("isValid", "true");
                
                logger.info("Sent message hash code: "+ message.hashCode() + " : " + taskName);
                producer.send(destination, message);
                
                session.close();
                connection.close();
            } catch (JMSException e) {
                logger.error("Sender createTask method error", e);
            }
        };
        new Thread(sendTask).start();
    }

    private String generateTaskName() {
        return new BigInteger(20, random).toString(16);
    }
}
