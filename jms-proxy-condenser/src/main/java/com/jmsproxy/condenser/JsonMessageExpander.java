package com.jmsproxy.condenser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmsproxy.core.CondensedMessageEnvelope;
import com.jmsproxy.core.MessageExpander;
import com.jmsproxy.core.OriginalMessageInfo;
import com.jmsproxy.core.util.JsonUtils;
import com.jmsproxy.core.util.MessageUtils;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Expander that reconstructs individual messages from condensed JSON messages.
 */
public class JsonMessageExpander implements MessageExpander {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonMessageExpander.class);
    
    private final Session session;
    private final ObjectMapper objectMapper;
    private final String timestampFieldName;
    
    public JsonMessageExpander(Session session) {
        this(session, "timestamp");
    }
    
    public JsonMessageExpander(Session session, String timestampFieldName) {
        this.session = session;
        this.objectMapper = JsonUtils.getObjectMapper();
        this.timestampFieldName = timestampFieldName;
    }
    
    @Override
    public boolean isCondensed(Message message) {
        String content = MessageUtils.getTextContent(message);
        if (content == null) {
            return false;
        }
        
        // Fast path: quick string check before full JSON parsing
        // Look for the marker without full parsing
        if (!content.contains("_condensedMeta")) {
            return false;
        }
        
        if (!JsonUtils.looksLikeJson(content)) {
            return false;
        }
        
        try {
            JsonNode node = objectMapper.readTree(content);
            JsonNode metaNode = node.get("_condensedMeta");
            return metaNode != null && metaNode.has("condensed") && metaNode.get("condensed").asBoolean();
        } catch (JsonProcessingException e) {
            return false;
        }
    }
    
    @Override
    public List<Message> expand(Message message) {
        if (!isCondensed(message)) {
            return Collections.singletonList(message);
        }
        
        String content = MessageUtils.getTextContent(message);
        if (content == null) {
            return Collections.singletonList(message);
        }
        
        try {
            JsonNode node = objectMapper.readTree(content);
            JsonNode metaNode = node.get("_condensedMeta");
            
            if (metaNode == null) {
                return Collections.singletonList(message);
            }
            
            int count = metaNode.has("count") ? metaNode.get("count").asInt() : 1;
            JsonNode timestampsNode = metaNode.get("originalTimestamps");
            
            List<Message> expandedMessages = new ArrayList<>();
            
            // Create base content without metadata
            ObjectNode baseNode = (ObjectNode) node.deepCopy();
            baseNode.remove("_condensedMeta");
            
            for (int i = 0; i < count; i++) {
                ObjectNode messageNode = baseNode.deepCopy();
                
                // Restore original timestamp if available
                if (timestampsNode != null && timestampsNode.isArray() && i < timestampsNode.size()) {
                    long timestamp = timestampsNode.get(i).asLong();
                    messageNode.put(timestampFieldName, timestamp);
                }
                
                String expandedContent = objectMapper.writeValueAsString(messageNode);
                TextMessage expandedMessage = session.createTextMessage(expandedContent);
                
                // Copy properties from original message
                copyMessageProperties(message, expandedMessage);
                
                expandedMessages.add(expandedMessage);
            }
            
            if (logger.isDebugEnabled()) {
                logger.debug("Expanded condensed message into {} individual messages", expandedMessages.size());
            }
            return expandedMessages;
            
        } catch (JsonProcessingException | JMSException e) {
            logger.error("Failed to expand condensed message", e);
            return Collections.singletonList(message);
        }
    }
    
    @Override
    public CondensedMessageEnvelope extractEnvelope(Message message) {
        String content = MessageUtils.getTextContent(message);
        if (content == null) {
            return null;
        }
        
        try {
            JsonNode node = objectMapper.readTree(content);
            JsonNode metaNode = node.get("_condensedMeta");
            
            if (metaNode == null || !metaNode.has("condensed")) {
                return null;
            }
            
            int count = metaNode.has("count") ? metaNode.get("count").asInt() : 1;
            List<OriginalMessageInfo> originalInfos = new ArrayList<>();
            
            // We don't have full original message info, but we can reconstruct timestamps
            JsonNode timestampsNode = metaNode.get("originalTimestamps");
            if (timestampsNode != null && timestampsNode.isArray()) {
                for (int i = 0; i < timestampsNode.size(); i++) {
                    long ts = timestampsNode.get(i).asLong();
                    originalInfos.add(new OriginalMessageInfo(null, ts, null, 4, 0, null));
                }
            }
            
            // Fill remaining with empty infos
            while (originalInfos.size() < count) {
                originalInfos.add(new OriginalMessageInfo(null, 0, null, 4, 0, null));
            }
            
            return new CondensedMessageEnvelope(content, originalInfos);
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to extract envelope", e);
            return null;
        }
    }
    
    private void copyMessageProperties(Message source, Message target) {
        try {
            target.setJMSCorrelationID(source.getJMSCorrelationID());
            target.setJMSType(source.getJMSType());
            target.setJMSPriority(source.getJMSPriority());
            
            // Copy custom properties
            var propertyNames = source.getPropertyNames();
            while (propertyNames.hasMoreElements()) {
                String name = (String) propertyNames.nextElement();
                if (!name.startsWith("JMS")) {
                    Object value = source.getObjectProperty(name);
                    target.setObjectProperty(name, value);
                }
            }
        } catch (JMSException e) {
            logger.warn("Failed to copy message properties", e);
        }
    }
}
