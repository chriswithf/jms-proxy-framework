package com.jmsproxy.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmsproxy.condenser.JsonMessageCondenser;
import com.jmsproxy.producer.ProxyMessageProducer;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class BenchmarkProducer {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkProducer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Random random = new Random();

    // Configuration from Env Vars
    private static final String BROKER_URL = System.getenv().getOrDefault("BROKER_URL", "tcp://localhost:61616");
    private static final String QUEUE_NAME = System.getenv().getOrDefault("QUEUE_NAME", "benchmark.queue");
    private static final boolean PROXY_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("PROXY_ENABLED", "true"));
    private static final int MSG_RATE = Integer.parseInt(System.getenv().getOrDefault("MSG_RATE", "10")); // msgs per second
    private static final int DURATION_SEC = Integer.parseInt(System.getenv().getOrDefault("DURATION_SEC", "60"));
    private static final int CONDENSER_WINDOW_MS = Integer.parseInt(System.getenv().getOrDefault("CONDENSER_WINDOW_MS", "1000"));
    private static final int CONDENSER_BATCH_SIZE = Integer.parseInt(System.getenv().getOrDefault("CONDENSER_BATCH_SIZE", "100"));

    public static void main(String[] args) throws Exception {
        logger.info("Starting BenchmarkProducer...");
        logger.info("Config: Proxy={}, Rate={}msg/s, Duration={}s", PROXY_ENABLED, MSG_RATE, DURATION_SEC);

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(BROKER_URL);
        Connection connection = null;
        
        // Retry logic for connection
        for (int i = 0; i < 30; i++) {
            try {
                connection = factory.createConnection();
                connection.start();
                logger.info("Connected to broker successfully.");
                break;
            } catch (JMSException e) {
                logger.warn("Failed to connect to broker (attempt {}/30). Retrying in 2s...", i + 1);
                Thread.sleep(2000);
            }
        }
        
        if (connection == null) {
            logger.error("Could not connect to broker after multiple attempts. Exiting.");
            System.exit(1);
        }

        try (Connection conn = connection) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(QUEUE_NAME);
            
            MessageProducer producer = session.createProducer(queue);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

            if (PROXY_ENABLED) {
                producer = ProxyMessageProducer.builder(producer, session)
                        .condenser(JsonMessageCondenser.builder()
                                .windowMs(CONDENSER_WINDOW_MS)
                                .maxBatchSize(CONDENSER_BATCH_SIZE)
                                .addTimestampField("timestamp")
                                .build())
                        .build();
            }

            AtomicLong sentCount = new AtomicLong(0);
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            
            long periodNs = 1_000_000_000L / MSG_RATE;
            
            // Weather station ID stays constant to allow condensation
            String stationId = "station-" + UUID.randomUUID().toString().substring(0, 8);
            
            // State for simulating stable sensor readings
            final double[] currentValues = new double[] { 20.0, 50.0 }; // temp, humidity

            MessageProducer finalProducer = producer;
            Runnable sendTask = () -> {
                try {
                    // Change values only every 10 messages to simulate stable sensor readings
                    // This allows the Condenser to aggregate identical readings
                    if (sentCount.get() % 10 == 0) {
                        currentValues[0] = 20.0 + random.nextGaussian();
                        currentValues[1] = 50.0 + random.nextGaussian();
                    }

                    ObjectNode json = mapper.createObjectNode();
                    json.put("stationId", stationId);
                    json.put("timestamp", System.currentTimeMillis());
                    json.put("temperature", currentValues[0]);
                    json.put("humidity", currentValues[1]);
                    json.put("location", "Lab-1"); // Constant

                    TextMessage message = session.createTextMessage(json.toString());
                    finalProducer.send(message);
                    sentCount.incrementAndGet();
                    
                    if (sentCount.get() % 100 == 0) {
                        logger.info("Sent {} messages", sentCount.get());
                    }
                } catch (Exception e) {
                    logger.error("Error sending message", e);
                }
            };

            executor.scheduleAtFixedRate(sendTask, 0, periodNs, TimeUnit.NANOSECONDS);

            // Run for duration
            Thread.sleep(DURATION_SEC * 1000L);
            
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            
            if (PROXY_ENABLED && finalProducer instanceof AutoCloseable) {
                 // Ensure buffer is flushed
                ((AutoCloseable) finalProducer).close();
            }
            
            logger.info("Benchmark finished. Total sent: {}", sentCount.get());
        }
    }
}
