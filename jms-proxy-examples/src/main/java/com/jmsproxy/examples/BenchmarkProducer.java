package com.jmsproxy.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmsproxy.condenser.JsonMessageCondenser;
import com.jmsproxy.criteria.ContentCriteria;
import com.jmsproxy.criteria.PropertyCriteria;
import com.jmsproxy.producer.ProxyMessageProducer;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
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
    private static final String CRITERIA_TYPE = System.getenv().getOrDefault("CRITERIA_TYPE", "CONDENSER");
    private static final int MSG_RATE = Integer.parseInt(System.getenv().getOrDefault("MSG_RATE", "10")); // msgs per second
    private static final int DURATION_SEC = Integer.parseInt(System.getenv().getOrDefault("DURATION_SEC", "60"));
    private static final int CONDENSER_WINDOW_MS = Integer.parseInt(System.getenv().getOrDefault("CONDENSER_WINDOW_MS", "1000"));
    private static final int CONDENSER_BATCH_SIZE = Integer.parseInt(System.getenv().getOrDefault("CONDENSER_BATCH_SIZE", "100"));

    public static void main(String[] args) throws Exception {
        logger.info("Starting BenchmarkProducer...");
        logger.info("Config: Proxy={}, Criteria={}, Rate={}msg/s, Duration={}s", PROXY_ENABLED, CRITERIA_TYPE, MSG_RATE, DURATION_SEC);

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
                var builder = ProxyMessageProducer.builder(producer, session);
                
                switch (CRITERIA_TYPE) {
                    case "CONDENSER":
                        builder.condenser(JsonMessageCondenser.builder()
                                .windowMs(CONDENSER_WINDOW_MS)
                                .maxBatchSize(CONDENSER_BATCH_SIZE)
                                .addTimestampField("timestamp")
                                .build());
                        break;
                    case "FILTER_PROPERTY":
                        builder.addCriteria(PropertyCriteria.equals("priority", "high"));
                        break;
                    case "FILTER_CONTENT":
                        builder.addCriteria(ContentCriteria.contains("important"));
                        break;
                    case "NO_OP":
                        // No criteria or condenser, just pass-through proxy
                        break;
                    default:
                        logger.warn("Unknown CRITERIA_TYPE: {}. Defaulting to CONDENSER.", CRITERIA_TYPE);
                        builder.condenser(JsonMessageCondenser.builder()
                                .windowMs(CONDENSER_WINDOW_MS)
                                .maxBatchSize(CONDENSER_BATCH_SIZE)
                                .addTimestampField("timestamp")
                                .build());
                }
                
                producer = builder.build();
            }

            AtomicLong sentCount = new AtomicLong(0);
            AtomicLong totalBytesGenerated = new AtomicLong(0);
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            
            long periodNs = 1_000_000_000L / MSG_RATE;
            
            // Weather station ID stays constant to allow condensation
            String stationId = "station-" + UUID.randomUUID().toString().substring(0, 8);
            
            // State for simulating stable sensor readings
            final double[] currentValues = new double[] { 20.0, 50.0 }; // temp, humidity
            final int[] deviceState = new int[] { 95, 4 }; // battery, signal
            final String[] firmware = new String[] { "v1.2.3" };
            final String[] payload = new String[] { "initial_payload_data" };

            MessageProducer finalProducer = producer;
            Runnable sendTask = () -> {
                try {
                    // Simulate sensor reading time (jitter)
                    Thread.sleep(random.nextInt(5));

                    // Change values only every 10 messages to simulate stable sensor readings
                    // This allows the Condenser to aggregate identical readings
                    if (sentCount.get() % 10 == 0) {
                        currentValues[0] = 20.0 + random.nextGaussian();
                        currentValues[1] = 50.0 + random.nextGaussian();
                        deviceState[0] = Math.max(0, deviceState[0] - (random.nextInt(2))); // drain battery slowly
                        deviceState[1] = random.nextInt(5); // signal strength 0-4
                        // Generate a random payload string of 50-100 chars
                        byte[] randomBytes = new byte[50 + random.nextInt(50)];
                        random.nextBytes(randomBytes);
                        payload[0] = UUID.randomUUID().toString() + "-sensor-data-" + sentCount.get();
                    }

                    ObjectNode json = mapper.createObjectNode();
                    json.put("stationId", stationId);
                    json.put("timestamp", System.currentTimeMillis());
                    json.put("temperature", currentValues[0]);
                    json.put("humidity", currentValues[1]);
                    json.put("battery", deviceState[0]);
                    json.put("signal", deviceState[1]);
                    json.put("firmware", firmware[0]);
                    json.put("payload", payload[0]);
                    json.put("location", "Lab-1"); // Constant
                    
                    // Add content for FILTER_CONTENT
                    if (sentCount.get() % 2 == 0) {
                        json.put("type", "important");
                    }

                    String jsonString = json.toString();
                    totalBytesGenerated.addAndGet(jsonString.getBytes(StandardCharsets.UTF_8).length);

                    TextMessage message = session.createTextMessage(jsonString);
                    
                    // Add property for FILTER_PROPERTY
                    message.setStringProperty("priority", sentCount.get() % 2 == 0 ? "high" : "low");
                    
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
            
            // Write stats
            if (PROXY_ENABLED && finalProducer instanceof ProxyMessageProducer) {
                Map<String, Long> stats = ((ProxyMessageProducer) finalProducer).getCondenserStats();
                try {
                    File resultsDir = new File("results");
                    if (!resultsDir.exists()) {
                        resultsDir.mkdirs();
                    }
                    
                    ObjectNode statsJson = mapper.createObjectNode();
                    statsJson.put("timestamp", Instant.now().toString());
                    statsJson.put("inputMessages", stats.getOrDefault("inputMessages", 0L));
                    statsJson.put("outputBatches", stats.getOrDefault("outputBatches", 0L));
                    statsJson.put("totalSent", sentCount.get());
                    statsJson.put("totalBytesGenerated", totalBytesGenerated.get());
                    statsJson.put("avgMessageSize", sentCount.get() > 0 ? totalBytesGenerated.get() / sentCount.get() : 0);
                    
                    String filename = String.format("results/producer_stats_%d.json", System.currentTimeMillis());
                    try (FileWriter writer = new FileWriter(filename)) {
                        writer.write(statsJson.toPrettyString());
                    }
                    logger.info("Producer stats written to {}", filename);
                } catch (IOException e) {
                    logger.error("Failed to write producer stats", e);
                }
            }
            
            if (PROXY_ENABLED && finalProducer instanceof AutoCloseable) {
                 // Ensure buffer is flushed
                ((AutoCloseable) finalProducer).close();
            }
            
            logger.info("Benchmark finished. Total sent: {}", sentCount.get());
        }
    }
}
