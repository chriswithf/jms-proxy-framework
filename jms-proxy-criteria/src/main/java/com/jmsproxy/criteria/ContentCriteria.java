package com.jmsproxy.criteria;

import com.jmsproxy.core.SendCriteria;
import com.jmsproxy.core.util.JsonUtils;
import com.jmsproxy.core.util.MessageUtils;
import jakarta.jms.Message;

import java.util.function.Predicate;

/**
 * Criteria based on message content (body).
 */
public class ContentCriteria implements SendCriteria {
    
    private final Predicate<String> contentPredicate;
    
    private ContentCriteria(Predicate<String> contentPredicate) {
        this.contentPredicate = contentPredicate;
    }
    
    /**
     * Creates a criteria that checks if content contains a substring.
     */
    public static ContentCriteria contains(String substring) {
        return new ContentCriteria(content -> 
            content != null && content.contains(substring));
    }
    
    /**
     * Creates a criteria that checks if content matches a regex.
     */
    public static ContentCriteria matchesRegex(String regex) {
        return new ContentCriteria(content -> 
            content != null && content.matches(regex));
    }
    
    /**
     * Creates a criteria that checks if content is valid JSON.
     */
    public static ContentCriteria isValidJson() {
        return new ContentCriteria(JsonUtils::isValidJson);
    }
    
    /**
     * Creates a criteria that checks a JSON field value.
     */
    public static ContentCriteria jsonFieldEquals(String fieldName, String expectedValue) {
        return new ContentCriteria(content -> {
            String fieldValue = JsonUtils.extractField(content, fieldName);
            return expectedValue.equals(fieldValue);
        });
    }
    
    /**
     * Creates a criteria that checks if a JSON field exists.
     */
    public static ContentCriteria jsonFieldExists(String fieldName) {
        return new ContentCriteria(content -> 
            JsonUtils.extractField(content, fieldName) != null);
    }
    
    /**
     * Creates a criteria with a custom predicate.
     */
    public static ContentCriteria custom(Predicate<String> predicate) {
        return new ContentCriteria(predicate);
    }
    
    /**
     * Creates a criteria that checks minimum content length.
     */
    public static ContentCriteria minLength(int minLength) {
        return new ContentCriteria(content -> 
            content != null && content.length() >= minLength);
    }
    
    /**
     * Creates a criteria that checks maximum content length.
     */
    public static ContentCriteria maxLength(int maxLength) {
        return new ContentCriteria(content -> 
            content != null && content.length() <= maxLength);
    }
    
    @Override
    public boolean evaluate(Message message) {
        String content = MessageUtils.getTextContent(message);
        return contentPredicate.test(content);
    }
}
