# JMS Proxy Framework

A transparent proxy framework for Jakarta JMS (ActiveMQ Artemis) that provides advanced features like message condensing (batching) and flexible filtering criteria without requiring significant changes to your existing application logic.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Project Structure](#project-structure)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Usage Guide](#usage-guide)
  - [Wrapping a Producer](#wrapping-a-producer)
  - [Wrapping a Consumer](#wrapping-a-consumer)
  - [Using Criteria (Filtering)](#using-criteria-filtering)
  - [Using the Condenser (Batching)](#using-the-condenser-batching)
- [Configuration Options](#configuration-options)
- [Examples](#examples)
- [Benchmarking](#benchmarking)
  - [Comprehensive Benchmark](#comprehensive-benchmark)
  - [Energy/Power Consumption Testing](#energypower-consumption-testing)
- [Docker Support](#docker-support)
- [Architecture](#architecture)
- [API Reference](#api-reference)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

The JMS Proxy Framework allows you to wrap standard JMS `MessageProducer` and `MessageConsumer` objects to inject additional behavior transparently. It is designed as a "drop-in" enhancement for existing JMS applications.

### Why Use This Framework?

- **Reduce Broker Load**: Message condensing batches multiple messages into one, reducing network overhead and broker processing.
- **Intelligent Filtering**: Apply criteria to filter messages before sending, reducing unnecessary traffic.
- **Zero Application Changes**: Consumers automatically expand condensed messages - your application logic remains unchanged.
- **Measurable Performance**: Built-in benchmarking tools to validate performance improvements in your environment.

---

## Key Features

| Feature | Description |
|---------|-------------|
| **Transparent Proxying** | Wrap existing JMS producers and consumers with minimal code changes |
| **Message Condensing** | Automatically aggregate multiple small messages into a single "condensed" message |
| **Auto-Expansion** | Consumer proxy automatically detects and expands condensed messages |
| **Send Criteria** | Apply flexible filtering rules based on properties, content, or time |
| **Builder Pattern** | Fluent API for easy configuration |
| **Performance Monitoring** | Built-in benchmarking with visualization |
| **Energy Testing** | Tools for measuring power consumption impact |

---

## Project Structure

```
jms-proxy-framework/
├── jms-proxy-core/          # Core interfaces and shared logic
├── jms-proxy-condenser/     # Message condensing strategies (JSON batching)
├── jms-proxy-criteria/      # Filtering criteria implementations
├── jms-proxy-producer/      # ProxyMessageProducer implementation
├── jms-proxy-consumer/      # BufferedProxyMessageConsumer implementation
├── jms-proxy-examples/      # Example applications and benchmarks
├── build.sh                 # Build script
├── run-example.sh           # Run integration example
├── run-proxy.sh             # Run proxy demonstration
├── run-comprehensive-benchmark.sh  # Performance benchmark
├── run-energy-test.sh       # Energy consumption testing
├── docker-compose.yml       # Docker orchestration
└── Dockerfile               # Container build configuration
```

### Module Overview

| Module | Description |
|--------|-------------|
| `jms-proxy-core` | Core interfaces (`Condenser`, `Criteria`), base classes, and utilities |
| `jms-proxy-condenser` | JSON-based message condensing implementation |
| `jms-proxy-criteria` | Pre-built criteria: Property, Content, Time-based filters |
| `jms-proxy-producer` | `ProxyMessageProducer` with builder pattern |
| `jms-proxy-consumer` | `BufferedProxyMessageConsumer` with auto-expansion |
| `jms-proxy-examples` | Integration examples, benchmarks, and demonstrations |

---

## Requirements

- **Java**: 17 or higher
- **Maven**: 3.6 or higher
- **Docker & Docker Compose**: Optional, for containerized examples
- **ActiveMQ Artemis**: Embedded (for tests) or external broker

---

## Installation

### Build from Source

```bash
# Clone the repository
git clone <repository-url>
cd jms-proxy-framework

# Build all modules
./build.sh

# Or using Maven directly
mvn clean install
```

### Maven Dependency

After building, you can include the modules in your project:

```xml
<dependency>
    <groupId>com.jmsproxy</groupId>
    <artifactId>jms-proxy-producer</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>com.jmsproxy</groupId>
    <artifactId>jms-proxy-consumer</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: Include criteria module for filtering -->
<dependency>
    <groupId>com.jmsproxy</groupId>
    <artifactId>jms-proxy-criteria</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

---

## Quick Start

### Minimal Example

```java
import com.jmsproxy.producer.ProxyMessageProducer;
import com.jmsproxy.consumer.BufferedProxyMessageConsumer;

// Wrap your producer
MessageProducer proxy = ProxyMessageProducer.wrap(originalProducer, session);
proxy.send(message);  // Use exactly like the original

// Wrap your consumer
MessageConsumer consumerProxy = BufferedProxyMessageConsumer.wrap(originalConsumer, session);
consumerProxy.setMessageListener(msg -> {
    // Receives individual messages, even if batched
    System.out.println("Received: " + ((TextMessage) msg).getText());
});
```

---

## Usage Guide

### Wrapping a Producer

#### Basic Usage (Default Settings)

```java
import com.jmsproxy.producer.ProxyMessageProducer;

// Create your standard producer
MessageProducer originalProducer = session.createProducer(destination);

// Wrap it with the proxy
MessageProducer proxyProducer = ProxyMessageProducer.wrap(originalProducer, session);

// Use proxyProducer exactly as you would the original
proxyProducer.send(message);
```

#### Using the Builder Pattern

```java
MessageProducer proxyProducer = ProxyMessageProducer
    .builder(session.createProducer(null), session)
    .withJsonCondenser(2000, 10)     // Batch: 2s window, max 10 messages
    .addCriteria(PropertyCriteria.equals("isValid", "true"))
    .build();

proxyProducer.send(destination, message);
```

### Wrapping a Consumer

```java
import com.jmsproxy.consumer.BufferedProxyMessageConsumer;

// Create your standard consumer
MessageConsumer originalConsumer = session.createConsumer(destination);

// Wrap it with the proxy
MessageConsumer proxyConsumer = BufferedProxyMessageConsumer.wrap(originalConsumer, session);

// Set your listener as usual
proxyConsumer.setMessageListener(message -> {
    // This listener receives individual messages, even if they arrived as a batch
    TextMessage tm = (TextMessage) message;
    System.out.println("Received: " + tm.getText());
});
```

### Using Criteria (Filtering)

Criteria allow you to filter messages before they are sent:

```java
import com.jmsproxy.criteria.PropertyCriteria;
import com.jmsproxy.criteria.ContentCriteria;
import com.jmsproxy.criteria.TimeCriteria;

// Property-based filtering
PropertyCriteria.equals("priority", "high")
PropertyCriteria.exists("correlationId")
PropertyCriteria.matches("type", "order.*")

// Content-based filtering
ContentCriteria.contains("important")
ContentCriteria.matches("\\d{4}-\\d{2}-\\d{2}")  // Date pattern

// Time-based filtering
TimeCriteria.between(startTime, endTime)
TimeCriteria.businessHoursOnly()

// Combine multiple criteria
MessageProducer proxy = ProxyMessageProducer
    .builder(producer, session)
    .addCriteria(PropertyCriteria.equals("isValid", "true"))
    .addCriteria(ContentCriteria.contains("order"))
    .build();
```

### Using the Condenser (Batching)

The condenser aggregates multiple messages into a single batched message:

```java
// Configure condensing: 
// - windowMs: Time window in milliseconds (flush after this time)
// - batchSize: Maximum messages per batch (flush when reached)

MessageProducer proxy = ProxyMessageProducer
    .builder(producer, session)
    .withJsonCondenser(2000, 10)  // 2 second window, max 10 messages
    .build();

// Messages are automatically batched
for (int i = 0; i < 100; i++) {
    proxy.send(destination, session.createTextMessage("Message " + i));
}
proxy.close();  // Flushes remaining messages
```

---

## Configuration Options

### ProxyMessageProducer Builder Options

| Method | Description | Default |
|--------|-------------|---------|
| `.withJsonCondenser(windowMs, batchSize)` | Enable JSON-based message batching | Disabled |
| `.disableCondenser()` | Explicitly disable condensing | - |
| `.addCriteria(Criteria)` | Add a filtering criterion | None |
| `.disableCriteria()` | Disable all criteria filtering | - |

### Condenser Parameters

| Parameter | Description | Recommended Value |
|-----------|-------------|-------------------|
| `windowMs` | Time window before forcing a flush | 1000-5000 ms |
| `batchSize` | Maximum messages before forcing a flush | 10-100 |

---

## Examples

### Running the Integration Example

```bash
./run-example.sh
```

This script will:
1. Build the Maven project
2. Build Docker images for ActiveMQ Artemis and the example app
3. Start the environment using Docker Compose
4. Run the multi-threaded sender/receiver example

### What the Example Does

The integration example (`it.janczewski.examples.App`) demonstrates:
- **10 Sender Threads**: Send messages using `ProxyMessageProducer` with criteria
- **10 Receiver Threads**: Receive messages using `BufferedProxyMessageConsumer`

Source code locations:
- `jms-proxy-examples/src/main/java/it/janczewski/examples/utils/Sender.java`
- `jms-proxy-examples/src/main/java/it/janczewski/examples/utils/Reciver.java`

---

## Benchmarking

### Comprehensive Benchmark

Run performance tests comparing baseline vs. proxy with various configurations:

```bash
./run-comprehensive-benchmark.sh
```

#### Features

- **Multiple Test Scenarios**: Baseline, Condenser only, Criteria only, Full proxy
- **Live Progress**: Real-time throughput visualization during tests
- **ASCII Charts**: Console-based throughput comparison charts
- **HTML Reports**: Interactive charts with Chart.js (`benchmark-report.html`)

#### Benchmark Output

```
============================================================
                 BENCHMARK RESULTS SUMMARY
============================================================
Scenario                    Sent    Received  Time(ms)  Throughput
--------------------------------------------------------------------
Baseline (No Proxy)        10000      10000     2340    4273.50 msg/s
Condenser Only             10000      10000     1856    5387.93 msg/s
Criteria Only              10000      10000     2412    4145.94 msg/s
Full Proxy                 10000      10000     1923    5200.21 msg/s
--------------------------------------------------------------------
```

### Energy/Power Consumption Testing

For measuring energy efficiency on devices with power monitoring:

```bash
./run-energy-test.sh
```

#### Interactive Mode

```bash
./run-energy-test.sh

# Menu options:
# 1) baseline    - Direct JMS (no proxy)
# 2) passthrough - Proxy without features
# 3) condenser   - Proxy with message batching
# 4) criteria    - Proxy with filtering
# 5) full        - All features enabled
# 6) all         - Run all modes sequentially
```

#### Command Line Mode

```bash
# Run specific mode with custom parameters
./run-energy-test.sh condenser --rate 500 --duration 60 --window 2000 --batch 20

# Run all modes with CSV output
./run-energy-test.sh all --duration 120 --csv

# Parameters:
#   --rate <n>     Messages per second (default: 100)
#   --duration <s> Test duration in seconds (default: 30)
#   --window <ms>  Condenser time window (default: 1000)
#   --batch <n>    Condenser batch size (default: 10)
#   --warmup <s>   Warmup period (default: 5)
#   --csv          Output results in CSV format
```

#### Energy Test Output

```
============================================================
          ENERGY BENCHMARK - CONDENSER MODE
============================================================
Configuration:
  Message Rate: 500 msg/s
  Duration: 60 seconds
  Window: 2000 ms | Batch Size: 20

Progress: ████████████████████████████████████████ 100%

Results:
  Messages Sent: 30000
  Messages Received: 30000
  Actual Duration: 60.23 seconds
  Actual Throughput: 498.09 msg/s
  
Use external power meter readings to compare energy consumption.
```

---

## Docker Support

### Docker Compose Setup

```bash
# Start ActiveMQ Artemis broker
docker-compose up -d broker

# Run the example application
docker-compose up app

# Stop all services
docker-compose down
```

### Building Docker Images

```bash
# Build all images
docker-compose build

# Build specific image
docker build -t jms-proxy-app .
```

### Embedded Broker (No Docker Required)

The benchmarks and energy tests use an embedded ActiveMQ Artemis broker, requiring no external dependencies:

```java
// Embedded broker is automatically started for tests
EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
broker.setConfigResourcePath("broker.xml");
broker.start();
```

---

## Architecture

For detailed architecture diagrams and technical documentation, see [ARCHITECTURE.md](ARCHITECTURE.md).

### High-Level Flow

```
┌─────────────────┐     ┌─────────────────────┐     ┌─────────────┐
│   Application   │────▶│  ProxyMessageProducer│────▶│   Broker    │
│                 │     │  ├─ Criteria        │     │  (Artemis)  │
│                 │     │  └─ Condenser       │     │             │
└─────────────────┘     └─────────────────────┘     └──────┬──────┘
                                                           │
┌─────────────────┐     ┌─────────────────────┐           │
│   Application   │◀────│BufferedProxyConsumer│◀──────────┘
│                 │     │  └─ Auto-Expand     │
└─────────────────┘     └─────────────────────┘
```

---

## API Reference

### ProxyMessageProducer

```java
// Static factory methods
ProxyMessageProducer.wrap(MessageProducer producer, Session session)
ProxyMessageProducer.builder(MessageProducer producer, Session session)

// Builder methods
.withJsonCondenser(long windowMs, int batchSize)
.disableCondenser()
.addCriteria(Criteria criteria)
.disableCriteria()
.build()
```

### BufferedProxyMessageConsumer

```java
// Static factory method
BufferedProxyMessageConsumer.wrap(MessageConsumer consumer, Session session)

// Standard MessageConsumer interface
.receive()
.receive(long timeout)
.receiveNoWait()
.setMessageListener(MessageListener listener)
.close()
```

### Criteria Interface

```java
public interface Criteria {
    boolean matches(Message message) throws JMSException;
}

// Built-in implementations
PropertyCriteria.equals(String property, String value)
PropertyCriteria.exists(String property)
PropertyCriteria.matches(String property, String regex)

ContentCriteria.contains(String substring)
ContentCriteria.matches(String regex)

TimeCriteria.between(LocalTime start, LocalTime end)
TimeCriteria.businessHoursOnly()
```

---

## Troubleshooting

### Common Issues

#### Import Errors: `javax.jms` vs `jakarta.jms`

This framework uses **Jakarta JMS** (jakarta.jms), not the legacy javax.jms:

```java
// Correct
import jakarta.jms.*;

// Incorrect (will not compile)
import javax.jms.*;
```

#### Messages Not Being Condensed

1. Ensure you're calling `.withJsonCondenser(windowMs, batchSize)`
2. Check that the window time hasn't elapsed
3. Verify `close()` is called to flush remaining messages

#### Consumer Not Receiving Expanded Messages

1. Ensure consumer is wrapped with `BufferedProxyMessageConsumer.wrap()`
2. Check that the message listener is set correctly
3. Verify broker connectivity

#### Build Failures

```bash
# Clean and rebuild
mvn clean install -DskipTests

# Check Java version
java -version  # Should be 17+
```

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Guidelines

- Follow existing code style
- Add unit tests for new features
- Update documentation as needed
- Run full build before submitting PRs

---

## License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## Acknowledgments

- Based on integration patterns from `tjancz/jms-activemq-example`
- Uses Apache ActiveMQ Artemis as the JMS broker
- Chart.js for benchmark visualization
