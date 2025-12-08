package it.janczewski.examples.utils;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jmsproxy.consumer.BufferedProxyMessageConsumer;

import jakarta.jms.*;

public class Reciver implements ExceptionListener {
    private final Logger logger = LoggerFactory.getLogger(Reciver.class);

    public void createRecieveTask() {
        Runnable recTask = () -> {
            try {
                ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                    System.getenv().getOrDefault("BROKER_URL", "tcp://broker:61616")
                );
                Connection connection = connectionFactory.createConnection();
                connection.start();
                connection.setExceptionListener(this);
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Destination destination = session.createQueue("TJ_Test");
                
                // Directly wrap the consumer with BufferedProxyMessageConsumer
                MessageConsumer consumer = BufferedProxyMessageConsumer.wrap(session.createConsumer(destination), session);


                // The original example uses receive(4000)
                Message message = consumer.receive(4000);
                
                if (message instanceof TextMessage) {
                    TextMessage textMessage = (TextMessage) message;
                    String text = textMessage.getText();
                   logger.info("Received TextMessage object: " + text);
                } else if (message != null) {
                    logger.info("Received other object type with message: " + message);
                } else {
                    logger.info("Received no message within timeout");
                }
                
                consumer.close();
                session.close();
                connection.close();

            } catch (JMSException e) {
                logger.error("Reciver createRecieveTask method error", e);
            }
        };
        new Thread(recTask).start();
    }

    @Override
    public void onException(JMSException exception) {
        logger.error("Recieve error occured.");
    }
}
