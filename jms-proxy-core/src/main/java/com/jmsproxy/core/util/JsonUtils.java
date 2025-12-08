package com.jmsproxy.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Utility class for JSON operations used in message comparison and condensing.
 */
public final class JsonUtils {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    private JsonUtils() {
        // Utility class
    }
    
    /**
     * Parses a JSON string into a JsonNode.
     * 
     * @param json The JSON string
     * @return The parsed JsonNode
     * @throws JsonProcessingException if parsing fails
     */
    public static JsonNode parse(String json) throws JsonProcessingException {
        return OBJECT_MAPPER.readTree(json);
    }
    
    /**
     * Converts an object to a JSON string.
     * 
     * @param object The object to convert
     * @return The JSON string
     * @throws JsonProcessingException if conversion fails
     */
    public static String toJson(Object object) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(object);
    }
    
    /**
     * Creates a normalized JSON string with fields in alphabetical order,
     * excluding specified fields.
     * 
     * @param json The original JSON string
     * @param excludeFields Fields to exclude from normalization
     * @return The normalized JSON string
     */
    public static String normalizeExcludingFields(String json, Set<String> excludeFields) {
        try {
            JsonNode node = parse(json);
            if (node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                excludeFields.forEach(objectNode::remove);
                return sortedJson(objectNode);
            }
            return json;
        } catch (JsonProcessingException e) {
            return json;
        }
    }
    
    /**
     * Creates a sorted JSON string from a JsonNode.
     * 
     * @param node The JsonNode to sort
     * @return A JSON string with sorted keys
     */
    public static String sortedJson(JsonNode node) {
        try {
            Object obj = OBJECT_MAPPER.treeToValue(node, Object.class);
            return OBJECT_MAPPER.writeValueAsString(sortObject(obj));
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }
    
    @SuppressWarnings("unchecked")
    private static Object sortObject(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            TreeMap<String, Object> sortedMap = new TreeMap<>();
            map.forEach((key, value) -> sortedMap.put(key, sortObject(value)));
            return sortedMap;
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            List<Object> sortedList = new ArrayList<>();
            list.forEach(item -> sortedList.add(sortObject(item)));
            return sortedList;
        }
        return obj;
    }
    
    /**
     * Extracts a field value from a JSON string.
     * 
     * @param json The JSON string
     * @param fieldName The field name to extract
     * @return The field value as a string, or null if not found
     */
    public static String extractField(String json, String fieldName) {
        try {
            JsonNode node = parse(json);
            JsonNode fieldNode = node.get(fieldName);
            return fieldNode != null ? fieldNode.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
    
    /**
     * Checks if a string is valid JSON.
     * 
     * @param content The string to check
     * @return true if the string is valid JSON
     */
    public static boolean isValidJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        try {
            parse(content);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
    
    /**
     * Gets the ObjectMapper instance.
     * 
     * @return The shared ObjectMapper
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
}
