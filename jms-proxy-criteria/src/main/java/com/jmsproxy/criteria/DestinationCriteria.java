package com.jmsproxy.criteria;

import com.jmsproxy.core.SendCriteria;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.Topic;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Criteria based on message destination.
 */
public class DestinationCriteria implements SendCriteria {
    
    private final DestinationMatcher matcher;
    
    private DestinationCriteria(DestinationMatcher matcher) {
        this.matcher = matcher;
    }
    
    /**
     * Creates a criteria that only allows messages to specific queues.
     */
    public static DestinationCriteria queuesOnly(String... queueNames) {
        Set<String> names = new HashSet<>(Arrays.asList(queueNames));
        return new DestinationCriteria(dest -> {
            if (dest instanceof Queue queue) {
                try {
                    return names.isEmpty() || names.contains(queue.getQueueName());
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        });
    }
    
    /**
     * Creates a criteria that only allows messages to specific topics.
     */
    public static DestinationCriteria topicsOnly(String... topicNames) {
        Set<String> names = new HashSet<>(Arrays.asList(topicNames));
        return new DestinationCriteria(dest -> {
            if (dest instanceof Topic topic) {
                try {
                    return names.isEmpty() || names.contains(topic.getTopicName());
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        });
    }
    
    /**
     * Creates a criteria that matches destination names by regex.
     */
    public static DestinationCriteria nameMatches(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return new DestinationCriteria(dest -> {
            String name = getDestinationName(dest);
            return name != null && pattern.matcher(name).matches();
        });
    }
    
    /**
     * Creates a criteria that matches destination names by prefix.
     */
    public static DestinationCriteria nameStartsWith(String prefix) {
        return new DestinationCriteria(dest -> {
            String name = getDestinationName(dest);
            return name != null && name.startsWith(prefix);
        });
    }
    
    /**
     * Creates a criteria that excludes specific destinations.
     */
    public static DestinationCriteria exclude(String... destinationNames) {
        Set<String> names = new HashSet<>(Arrays.asList(destinationNames));
        return new DestinationCriteria(dest -> {
            String name = getDestinationName(dest);
            return name == null || !names.contains(name);
        });
    }
    
    private static String getDestinationName(Destination dest) {
        try {
            if (dest instanceof Queue queue) {
                return queue.getQueueName();
            } else if (dest instanceof Topic topic) {
                return topic.getTopicName();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    @Override
    public boolean evaluate(Message message) {
        try {
            Destination dest = message.getJMSDestination();
            return matcher.matches(dest);
        } catch (Exception e) {
            return false;
        }
    }
    
    @FunctionalInterface
    private interface DestinationMatcher {
        boolean matches(Destination destination);
    }
}
