# JMS Proxy Framework - Architecture Documentation

This document provides detailed architecture diagrams and technical explanations for the JMS Proxy Framework.

## Table of Contents

1. [Component Overview](#component-overview)
2. [Class Structure](#class-structure)
3. [Producer Flow](#producer-flow)
4. [Consumer Flow](#consumer-flow)
5. [Condensing Strategy](#condensing-strategy)
6. [Criteria System](#criteria-system)

---

## Component Overview

The JMS Proxy Framework consists of several modules that work together to provide transparent message proxying, condensing, and filtering.

```mermaid
graph TB
    subgraph "Application Layer"
        APP[Application Code]
    end
    
    subgraph "Proxy Layer"
        PRODUCER[ProxyMessageProducer]
        CONSUMER[BufferedProxyMessageConsumer]
    end
    
    subgraph "Core Components"
        CONDENSER[MessageCondenser]
        EXPANDER[MessageExpander]
        CRITERIA[SendCriteria]
        CONFIG[ProxyConfiguration]
    end
    
    subgraph "JMS Layer"
        JMS_PRODUCER[JMS MessageProducer]
        JMS_CONSUMER[JMS MessageConsumer]
        BROKER[ActiveMQ Artemis]
    end
    
    APP -->|creates & wraps| PRODUCER
    APP -->|creates & wraps| CONSUMER
    
    PRODUCER -->|uses| CONDENSER
    PRODUCER -->|evaluates| CRITERIA
    PRODUCER -->|configured by| CONFIG
    PRODUCER -->|delegates to| JMS_PRODUCER
    
    CONSUMER -->|uses| EXPANDER
    CONSUMER -->|configured by| CONFIG
    CONSUMER -->|delegates to| JMS_CONSUMER
    
    JMS_PRODUCER -->|sends to| BROKER
    BROKER -->|delivers to| JMS_CONSUMER
    
    style APP fill:#e1f5ff
    style PRODUCER fill:#ffe1e1
    style CONSUMER fill:#ffe1e1
    style BROKER fill:#e8f5e8
```

---

## Class Structure

This diagram shows the main classes and their relationships within the framework.

```mermaid
classDiagram
    class MessageProducer {
        <<interface>>
        +send(Message)
        +send(Destination, Message)
        +close()
    }
    
    class ProxyMessageProducer {
        -MessageProducer delegate
        -MessageCondenser condenser
        -List~SendCriteria~ criteria
        -ProxyConfiguration config
        +send(Message)
        +send(Destination, Message)
        +flush()
        -evaluateCriteria(Message) boolean
    }
    
    class MessageCondenser {
        <<interface>>
        +shouldCondense(Message) boolean
        +addMessage(Message)
        +flush()
        +getBufferedMessageCount() int
    }
    
    class JsonMessageCondenser {
        -MessageComparisonStrategy strategy
        -Map~String, BufferedMessage~ buffer
        -long windowMs
        -int maxBatchSize
        +createAggregatedContent() String
        -computeKey(Message) String
    }
    
    class MessageComparisonStrategy {
        <<interface>>
        +computeComparisonKey(String jsonContent) String
    }
    
    class JsonFieldExclusionStrategy {
        -Set~String~ excludeFields
        +excludeField(String) Builder
        +computeComparisonKey(String) String
    }
    
    class SendCriteria {
        <<interface>>
        +evaluate(Message) boolean
    }
    
    class PropertyCriteria {
        -String propertyName
        -PropertyMatcher matcher
        +equals(String, String) PropertyCriteria
        +exists(String) PropertyCriteria
        +matches(String, Pattern) PropertyCriteria
    }
    
    class TimeCriteria {
        -RateLimiter limiter
        +rateLimit(int) TimeCriteria
        +businessHours() TimeCriteria
        +weekend() TimeCriteria
    }
    
    class ContentCriteria {
        -Predicate~String~ predicate
        +isValidJson() ContentCriteria
        +matchesRegex(Pattern) ContentCriteria
        +minLength(int) ContentCriteria
    }
    
    class MessageConsumer {
        <<interface>>
        +receive() Message
        +setMessageListener(MessageListener)
        +close()
    }
    
    class BufferedProxyMessageConsumer {
        -MessageConsumer delegate
        -MessageExpander expander
        -BlockingQueue~Message~ expandedMessageQueue
        +receive() Message
        +receive(long timeout) Message
        -expandAndQueue(Message)
    }
    
    class MessageExpander {
        <<interface>>
        +isCondensed(Message) boolean
        +expand(Message) List~Message~
    }
    
    class JsonMessageExpander {
        -Session session
        +expand(Message) List~Message~
        -reconstructMessage(OriginalMessageInfo) Message
    }
    
    MessageProducer <|.. ProxyMessageProducer
    ProxyMessageProducer --> MessageCondenser
    ProxyMessageProducer --> SendCriteria
    
    MessageCondenser <|.. JsonMessageCondenser
    JsonMessageCondenser --> MessageComparisonStrategy
    MessageComparisonStrategy <|.. JsonFieldExclusionStrategy
    
    SendCriteria <|.. PropertyCriteria
    SendCriteria <|.. TimeCriteria
    SendCriteria <|.. ContentCriteria
    
    MessageConsumer <|.. BufferedProxyMessageConsumer
    BufferedProxyMessageConsumer --> MessageExpander
    MessageExpander <|.. JsonMessageExpander
```

---

## Producer Flow

This sequence diagram illustrates what happens when an application sends a message through the `ProxyMessageProducer`.

```mermaid
sequenceDiagram
    participant App as Application
    participant Proxy as ProxyMessageProducer
    participant Criteria as SendCriteria
    participant Condenser as JsonMessageCondenser
    participant Delegate as JMS Producer
    participant Broker as ActiveMQ Broker

    App->>Proxy: send(destination, message)
    
    Note over Proxy: Check if criteria enabled
    
    alt Criteria Enabled
        loop For Each Criterion
            Proxy->>Criteria: evaluate(message)
            Criteria-->>Proxy: boolean result
            
            alt Criterion Failed
                Proxy-->>App: return (message blocked)
                Note over Proxy: Message not sent
            end
        end
    end
    
    Note over Proxy: All criteria passed
    
    alt Condenser Enabled
        Proxy->>Condenser: shouldCondense(message)
        Condenser-->>Proxy: true
        
        Proxy->>Condenser: addMessage(message)
        Note over Condenser: Message buffered
        
        alt Buffer Full OR Time Window Expired
            Condenser->>Condenser: createAggregatedContent()
            Note over Condenser: Merge JSONs into envelope:<br/>{messages: [...], count: N}
            
            Condenser->>Delegate: send(condensedMessage)
            Delegate->>Broker: transmit
            Note over Broker: Single condensed message<br/>stored in queue
        else Buffer Not Full
            Note over Condenser: Wait for more messages
        end
    else Condenser Disabled
        Proxy->>Delegate: send(destination, message)
        Delegate->>Broker: transmit
    end
    
    Proxy-->>App: return
```

**Key Points:**
1. **Criteria Evaluation**: Messages are checked against all registered criteria before processing.
2. **Buffering**: If condensing is enabled, messages are buffered until the time window expires or batch size is reached.
3. **Aggregation**: Multiple messages are merged into a single JSON envelope with metadata about the originals.
4. **Flushing**: A background scheduler periodically flushes the condenser to prevent messages from being held indefinitely.

---

## Consumer Flow

This sequence diagram shows how the `BufferedProxyMessageConsumer` handles incoming messages, including automatic expansion of condensed messages.

```mermaid
sequenceDiagram
    participant Broker as ActiveMQ Broker
    participant Delegate as JMS Consumer
    participant Proxy as BufferedProxyMessageConsumer
    participant Expander as JsonMessageExpander
    participant Queue as Internal Buffer Queue
    participant Listener as MessageListener (App)

    Broker->>Delegate: deliver(message)
    Delegate->>Proxy: onMessage(message)
    
    Proxy->>Expander: isCondensed(message)
    
    alt Message is Condensed
        Expander-->>Proxy: true
        
        Proxy->>Expander: expand(message)
        Note over Expander: Parse JSON envelope<br/>Extract original messages<br/>Reconstruct metadata
        
        Expander-->>Proxy: List[msg1, msg2, msg3, ...]
        
        loop For Each Expanded Message
            Proxy->>Queue: offer(expandedMsg)
            Note over Queue: Messages queued for<br/>sequential delivery
        end
        
        loop While Queue Not Empty
            Proxy->>Queue: poll()
            Queue-->>Proxy: expandedMsg
            Proxy->>Listener: onMessage(expandedMsg)
            Note over Listener: App processes message<br/>unaware of condensing
            Listener-->>Proxy: return
        end
        
    else Message is Regular
        Expander-->>Proxy: false
        Proxy->>Listener: onMessage(message)
        Listener-->>Proxy: return
    end
    
    Proxy-->>Delegate: return
```

**Key Points:**
1. **Detection**: The expander checks if a message is condensed by looking for specific properties or structure.
2. **Expansion**: Condensed messages are unpacked into their original individual messages.
3. **Buffering**: Expanded messages are queued to ensure sequential delivery.
4. **Transparency**: The application's `MessageListener` receives individual messages, completely unaware of the batching.

---

## Condensing Strategy

The condensing mechanism uses a sophisticated comparison strategy to group similar messages together.

```mermaid
graph TB
    subgraph "Message Comparison Flow"
        M1[Message A arrives]
        M2[Extract JSON content]
        M3{Comparison Strategy}
        M4[Compute Key]
        M5{Key exists in buffer?}
        M6[Add to existing batch]
        M7[Create new batch]
        M8[Store in buffer]
    end
    
    subgraph "Strategy Types"
        S1[JsonFieldExclusionStrategy]
        S2[Custom Strategy]
    end
    
    M1 --> M2
    M2 --> M3
    M3 --> S1
    M3 --> S2
    S1 --> M4
    S2 --> M4
    M4 --> M5
    M5 -->|Yes| M6
    M5 -->|No| M7
    M6 --> M8
    M7 --> M8
    
    style M3 fill:#ffe1e1
    style S1 fill:#fff4e1
    style S2 fill:#fff4e1
```

**Example: Field Exclusion Strategy**

```java
// Configure to ignore timestamp and eventId when comparing messages
JsonMessageCondenser condenser = JsonMessageCondenser.builder()
    .comparisonStrategy(
        JsonFieldExclusionStrategy.builder()
            .excludeField("timestamp")
            .excludeField("eventId")
            .build()
    )
    .windowMs(2000)
    .maxBatchSize(10)
    .build();
```

**How it works:**
1. Two messages with content `{"user": "john", "timestamp": "2025-12-08T10:00:00"}` and `{"user": "john", "timestamp": "2025-12-08T10:00:05"}` will be considered "similar" because the `timestamp` field is excluded.
2. They will be batched together into a single condensed message.
3. The condensed message contains both originals in a JSON array.

---

## Criteria System

The criteria system provides a flexible way to filter messages before they are sent. Multiple criteria can be combined using logical operators.

```mermaid
graph TB
    subgraph "Criteria Evaluation"
        MSG[Incoming Message]
        EVAL{Evaluate All Criteria}
        C1[PropertyCriteria]
        C2[TimeCriteria]
        C3[ContentCriteria]
        C4[DestinationCriteria]
        PASS[All Pass?]
        SEND[Send Message]
        BLOCK[Block Message]
    end
    
    MSG --> EVAL
    EVAL --> C1
    EVAL --> C2
    EVAL --> C3
    EVAL --> C4
    
    C1 --> PASS
    C2 --> PASS
    C3 --> PASS
    C4 --> PASS
    
    PASS -->|Yes| SEND
    PASS -->|No| BLOCK
    
    style PASS fill:#ffe1e1
    style SEND fill:#e8f5e8
    style BLOCK fill:#ffe8e8
```

**Available Criteria Types:**

1. **PropertyCriteria**: Filter based on message properties
   ```java
   PropertyCriteria.equals("isValid", "true")
   PropertyCriteria.exists("userId")
   PropertyCriteria.matches("email", Pattern.compile(".*@example\\.com"))
   ```

2. **TimeCriteria**: Filter based on time constraints
   ```java
   TimeCriteria.rateLimit(100)  // Max 100 messages per second
   TimeCriteria.businessHours()  // Only 9 AM - 5 PM
   TimeCriteria.weekend()        // Only weekends
   ```

3. **ContentCriteria**: Filter based on message content
   ```java
   ContentCriteria.isValidJson()
   ContentCriteria.matchesRegex(Pattern.compile(".*error.*"))
   ContentCriteria.minLength(10)
   ```

4. **CompositeCriteria**: Combine multiple criteria
   ```java
   CompositeCriteria.allOf(
       PropertyCriteria.equals("type", "order"),
       ContentCriteria.isValidJson(),
       TimeCriteria.businessHours()
   )
   ```

**Example: Complex Filtering**

```java
MessageProducer producer = ProxyMessageProducer.builder(rawProducer, session)
    .addCriteria(
        CompositeCriteria.allOf(
            PropertyCriteria.equals("priority", "high"),
            TimeCriteria.rateLimit(50),
            ContentCriteria.minLength(100)
        )
    )
    .build();
```

This configuration will:
- Only send messages where `priority` property is "high"
- Limit to 50 messages per second
- Require content length of at least 100 characters

---

## Configuration

The `ProxyConfiguration` class provides centralized control over proxy behavior:

```mermaid
classDiagram
    class ProxyConfiguration {
        -boolean condenserEnabled
        -boolean criteriaEnabled
        -long condenserWindowMs
        -int condenserMaxBatchSize
        -long flushIntervalMs
        +builder() Builder
        +passThrough() ProxyConfiguration
    }
    
    class Builder {
        +condenserEnabled(boolean) Builder
        +criteriaEnabled(boolean) Builder
        +condenserWindowMs(long) Builder
        +condenserMaxBatchSize(int) Builder
        +flushIntervalMs(long) Builder
        +build() ProxyConfiguration
    }
    
    ProxyConfiguration --> Builder
```

**Example Configurations:**

```java
// Default configuration: Condensing and criteria enabled
ProxyConfiguration config = ProxyConfiguration.builder().build();

// Pass-through mode: No condensing, no criteria
ProxyConfiguration config = ProxyConfiguration.passThrough();

// Custom configuration
ProxyConfiguration config = ProxyConfiguration.builder()
    .condenserEnabled(true)
    .condenserWindowMs(5000)      // 5 second window
    .condenserMaxBatchSize(50)    // Max 50 messages per batch
    .criteriaEnabled(true)
    .flushIntervalMs(1000)        // Flush every second
    .build();
```

---

## Module Dependencies

```mermaid
graph LR
    EXAMPLES[jms-proxy-examples]
    PRODUCER[jms-proxy-producer]
    CONSUMER[jms-proxy-consumer]
    CRITERIA[jms-proxy-criteria]
    CONDENSER[jms-proxy-condenser]
    CORE[jms-proxy-core]
    
    EXAMPLES --> PRODUCER
    EXAMPLES --> CONSUMER
    EXAMPLES --> CRITERIA
    
    PRODUCER --> CONDENSER
    PRODUCER --> CRITERIA
    PRODUCER --> CORE
    
    CONSUMER --> CONDENSER
    CONSUMER --> CORE
    
    CONDENSER --> CORE
    CRITERIA --> CORE
    
    style CORE fill:#e8f5e8
    style EXAMPLES fill:#e1f5ff
```

**Dependency Rationale:**
- **jms-proxy-core**: Foundation module with interfaces and shared utilities
- **jms-proxy-condenser**: Implements message aggregation strategies
- **jms-proxy-criteria**: Pre-built filtering criteria
- **jms-proxy-producer**: Producer proxy implementation
- **jms-proxy-consumer**: Consumer proxy implementation
- **jms-proxy-examples**: Demo applications and integration tests
