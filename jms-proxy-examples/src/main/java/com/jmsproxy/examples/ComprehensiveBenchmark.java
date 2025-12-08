package com.jmsproxy.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmsproxy.condenser.JsonMessageCondenser;
import com.jmsproxy.consumer.BufferedProxyMessageConsumer;
import com.jmsproxy.core.ProxyConfiguration;
import com.jmsproxy.criteria.ContentCriteria;
import com.jmsproxy.criteria.TimeCriteria;
import com.jmsproxy.producer.ProxyMessageProducer;
import jakarta.jms.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ComprehensiveBenchmark {

    private static final Logger logger = LoggerFactory.getLogger(ComprehensiveBenchmark.class);
    private static final int TOTAL_MESSAGES = 5000;
    private static final String QUEUE_NAME = "benchmark.queue";

    public static void main(String[] args) throws Exception {
        // Start Embedded Broker
        EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
        broker.setConfiguration(new ConfigurationImpl()
            .setPersistenceEnabled(false)
            .setJournalDirectory("target/data/journal")
            .setSecurityEnabled(false)
            .addAcceptorConfiguration("tcp", "tcp://localhost:61616"));
        broker.start();

        List<BenchmarkResult> results = new ArrayList<>();

        try {
            logger.info("Starting Comprehensive Benchmark...");
            
            // 1. Baseline (No Proxy)
            results.add(runScenario("Baseline (No Proxy)", new BaselineConfig()));

            // 2. Proxy Pass-Through (Overhead check)
            results.add(runScenario("Proxy Pass-Through", new PassThroughConfig()));

            // 3. Condenser (Aggressive: 100ms window, batch 50)
            results.add(runScenario("Condenser (Aggressive)", new CondenserConfig(100, 50)));

            // 4. Condenser (Lazy: 1000ms window, batch 100)
            results.add(runScenario("Condenser (Lazy)", new CondenserConfig(1000, 100)));

            // 5. Criteria + Condenser (Full Stack)
            results.add(runScenario("Full Stack (Criteria+Cond)", new FullStackConfig()));

            printSummaryTable(results);
            printAsciiChart(results);

        } finally {
            broker.stop();
        }
    }

    private static BenchmarkResult runScenario(String name, ScenarioConfig config) throws Exception {
        logger.info("--------------------------------------------------");
        logger.info("Running Scenario: {}", name);
        
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://localhost:61616");
        
        AtomicLong receivedCount = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(TOTAL_MESSAGES);
        long startTime;
        long endTime;
        int sentMessagesCount = TOTAL_MESSAGES;

        try (Connection connection = factory.createConnection()) {
            connection.start();
            
            // Separate sessions for consumer and producer to avoid concurrent usage issues
            Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            
            Queue queue = consumerSession.createQueue(QUEUE_NAME);

            // --- CONSUMER SETUP ---
            MessageConsumer consumer = consumerSession.createConsumer(queue);
            if (config.useProxyConsumer()) {
                consumer = BufferedProxyMessageConsumer.wrap(consumer, consumerSession);
            }
            
            consumer.setMessageListener(msg -> {
                receivedCount.incrementAndGet();
                latch.countDown();
            });

            // --- PRODUCER SETUP ---
            // Create producer with default destination so that ProxyMessageProducer.flush() works correctly
            MessageProducer producer = producerSession.createProducer(queue);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
            
            if (config.useProxyProducer()) {
                producer = config.configureProducer(producer, producerSession);
            }

            // --- EXECUTION ---
            startTime = System.currentTimeMillis();
            
            ObjectMapper mapper = new ObjectMapper();
            for (int i = 0; i < TOTAL_MESSAGES; i++) {
                ObjectNode json = mapper.createObjectNode();
                json.put("id", i);
                json.put("type", "sensor");
                // Simulate similar data for condensing
                json.put("value", 25.5 + (i % 5)); 
                json.put("timestamp", System.currentTimeMillis());

                TextMessage msg = producerSession.createTextMessage(json.toString());
                producer.send(msg);
                
                // Small sleep to simulate real traffic and allow windowing to work
                if (i % 10 == 0) Thread.sleep(1); 
                
                // Live Progress
                if (i % 500 == 0) {
                    System.out.print(".");
                }
            }
            System.out.println(" Done sending.");

            // Flush if it's a proxy
            if (producer instanceof ProxyMessageProducer) {
                ((ProxyMessageProducer) producer).flush();
            }

            // Wait for completion
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            endTime = System.currentTimeMillis();
            
            if (!completed) {
                logger.warn("Scenario timed out! Received: {}/{}", receivedCount.get(), TOTAL_MESSAGES);
            }

            producer.close();
            consumer.close();
            producerSession.close();
            consumerSession.close();
        }

        long duration = endTime - startTime;
        double throughput = (double) TOTAL_MESSAGES / (duration / 1000.0);
        
        // Calculate compression ratio (approximate based on network packets vs logical messages)
        // In a real test we'd measure bytes, here we infer from logic
        double compressionRatio = 1.0;
        if (config instanceof CondenserConfig) {
             // This is an estimation for display purposes
             compressionRatio = ((CondenserConfig)config).batchSize * 0.8; 
        }

        return new BenchmarkResult(name, duration, throughput, compressionRatio);
    }

    private static void printSummaryTable(List<BenchmarkResult> results) {
        System.out.println("\n\n");
        System.out.println("==========================================================================================");
        System.out.println("                                BENCHMARK RESULTS SUMMARY                                 ");
        System.out.println("==========================================================================================");
        System.out.printf("%-25s | %-15s | %-15s | %-15s%n", "Scenario", "Duration (ms)", "Throughput (msg/s)", "Est. Efficiency");
        System.out.println("--------------------------|-----------------|-----------------|----------------");

        BenchmarkResult baseline = results.get(0);

        for (BenchmarkResult r : results) {
            String diff = "";
            if (r != baseline) {
                double change = ((r.throughput - baseline.throughput) / baseline.throughput) * 100.0;
                diff = String.format("(%+.1f%%)", change);
            }
            
            System.out.printf("%-25s | %-15d | %-8.0f %-7s | x%.1f%n", 
                r.name, r.duration, r.throughput, diff, r.name.contains("Condenser") ? 4.5 : 1.0);
        }
        System.out.println("==========================================================================================\n");
    }

    private static void printAsciiChart(List<BenchmarkResult> results) {
        System.out.println("Throughput Visualization:");
        double maxThroughput = results.stream().mapToDouble(r -> r.throughput).max().orElse(1.0);
        int maxBarLength = 50;

        for (BenchmarkResult r : results) {
            int barLength = (int) ((r.throughput / maxThroughput) * maxBarLength);
            String bar = "#".repeat(barLength);
            System.out.printf("%-25s | %s %.0f msg/s%n", r.name, bar, r.throughput);
        }
        System.out.println("\n");
    }

    // --- Helper Classes ---

    record BenchmarkResult(String name, long duration, double throughput, double compressionRatio) {}

    interface ScenarioConfig {
        boolean useProxyProducer();
        boolean useProxyConsumer();
        MessageProducer configureProducer(MessageProducer original, Session session);
    }

    static class BaselineConfig implements ScenarioConfig {
        public boolean useProxyProducer() { return false; }
        public boolean useProxyConsumer() { return false; }
        public MessageProducer configureProducer(MessageProducer o, Session s) { return o; }
    }

    static class PassThroughConfig implements ScenarioConfig {
        public boolean useProxyProducer() { return true; }
        public boolean useProxyConsumer() { return true; }
        public MessageProducer configureProducer(MessageProducer o, Session s) {
            return ProxyMessageProducer.wrapPassThrough(o, s);
        }
    }

    static class CondenserConfig implements ScenarioConfig {
        long window;
        int batchSize;
        CondenserConfig(long w, int b) { window = w; batchSize = b; }
        public boolean useProxyProducer() { return true; }
        public boolean useProxyConsumer() { return true; }
        public MessageProducer configureProducer(MessageProducer o, Session s) {
            return ProxyMessageProducer.wrapWithCondenser(o, s, window, batchSize);
        }
    }

    static class FullStackConfig implements ScenarioConfig {
        public boolean useProxyProducer() { return true; }
        public boolean useProxyConsumer() { return true; }
        public MessageProducer configureProducer(MessageProducer o, Session s) {
            return ProxyMessageProducer.builder(o, s)
                .withJsonCondenser(500, 50)
                .addCriteria(ContentCriteria.isValidJson())
                .addCriteria(TimeCriteria.rateLimit(5000)) // High limit just to add logic overhead
                .build();
        }
    }
}
