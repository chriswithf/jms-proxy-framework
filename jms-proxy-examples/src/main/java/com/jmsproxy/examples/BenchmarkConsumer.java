package com.jmsproxy.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmsproxy.consumer.BufferedProxyMessageConsumer;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;

public class BenchmarkConsumer {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private static final String BROKER_URL = System.getenv().getOrDefault("BROKER_URL", "tcp://localhost:61616");
    private static final String QUEUE_NAME = System.getenv().getOrDefault("QUEUE_NAME", "benchmark.queue");
    private static final boolean PROXY_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("PROXY_ENABLED", "true"));

    // Stats
    private static final AtomicLong receivedCount = new AtomicLong(0);
    private static final LongAccumulator totalLatency = new LongAccumulator(Long::sum, 0);
    private static final LongAccumulator minLatency = new LongAccumulator(Math::min, Long.MAX_VALUE);
    private static final LongAccumulator maxLatency = new LongAccumulator(Math::max, 0);
    private static final long startTime = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        logger.info("Starting BenchmarkConsumer...");
        logger.info("Config: Proxy={}", PROXY_ENABLED);

        // Add shutdown hook for report
        Runtime.getRuntime().addShutdownHook(new Thread(() -> printReport()));

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
            
            MessageConsumer consumer = session.createConsumer(queue);
            
            if (PROXY_ENABLED) {
                consumer = BufferedProxyMessageConsumer.builder(consumer, session)
                        .timestampFieldName("timestamp")
                        .build();
            }

            consumer.setMessageListener(message -> {
                long count = receivedCount.incrementAndGet();
                long now = System.currentTimeMillis();
                
                try {
                    if (message instanceof TextMessage) {
                        String text = ((TextMessage) message).getText();
                        JsonNode json = mapper.readTree(text);
                        if (json.has("timestamp")) {
                            long sentTime = json.get("timestamp").asLong();
                            long latency = now - sentTime;
                            
                            totalLatency.accumulate(latency);
                            minLatency.accumulate(latency);
                            maxLatency.accumulate(latency);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error processing message", e);
                }

                if (count % 100 == 0) {
                    long elapsed = now - startTime;
                    double rate = (count * 1000.0) / elapsed;
                    logger.info("Received {} messages. Rate: {:.2f} msg/s", count, rate);
                }
            });

            // Keep alive
            Thread.currentThread().join();
        }
    }

    private static void printReport() {
        long count = receivedCount.get();
        long duration = System.currentTimeMillis() - startTime;
        double rate = (count * 1000.0) / duration;
        double avgLatency = count > 0 ? totalLatency.get() / (double) count : 0;

        System.out.println("\n\n================================================================");
        System.out.println("BENCHMARK REPORT (" + (PROXY_ENABLED ? "PROXY" : "BASELINE") + ")");
        System.out.println("================================================================");
        System.out.printf("Total Messages Received: %d%n", count);
        System.out.printf("Duration:                %.2f sec%n", duration / 1000.0);
        System.out.printf("Throughput:              %.2f msg/s%n", rate);
        System.out.println("----------------------------------------------------------------");
        System.out.printf("Avg Latency:             %.2f ms%n", avgLatency);
        System.out.printf("Min Latency:             %d ms%n", minLatency.get() == Long.MAX_VALUE ? 0 : minLatency.get());
        System.out.printf("Max Latency:             %d ms%n", maxLatency.get());
        System.out.println("================================================================\n");
    }
}
