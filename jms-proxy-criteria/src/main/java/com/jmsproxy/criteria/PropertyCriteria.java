package com.jmsproxy.criteria;

import com.jmsproxy.core.SendCriteria;
import com.jmsproxy.core.util.MessageUtils;
import jakarta.jms.Message;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Criteria based on message properties.
 */
public class PropertyCriteria implements SendCriteria {
    
    private final String propertyName;
    private final PropertyMatcher matcher;
    
    private PropertyCriteria(String propertyName, PropertyMatcher matcher) {
        this.propertyName = propertyName;
        this.matcher = matcher;
    }
    
    /**
     * Creates a criteria that checks if a property exists.
     */
    public static PropertyCriteria exists(String propertyName) {
        return new PropertyCriteria(propertyName, value -> value != null);
    }
    
    /**
     * Creates a criteria that checks if a property equals a value.
     */
    public static PropertyCriteria equals(String propertyName, String expectedValue) {
        return new PropertyCriteria(propertyName, 
            value -> expectedValue.equals(value));
    }
    
    /**
     * Creates a criteria that checks if a property is in a set of values.
     */
    public static PropertyCriteria in(String propertyName, String... values) {
        Set<String> valueSet = new HashSet<>(Arrays.asList(values));
        return new PropertyCriteria(propertyName, valueSet::contains);
    }
    
    /**
     * Creates a criteria that checks if a property matches a regex.
     */
    public static PropertyCriteria matches(String propertyName, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return new PropertyCriteria(propertyName, 
            value -> value != null && pattern.matcher(value).matches());
    }
    
    /**
     * Creates a criteria that checks if a property starts with a prefix.
     */
    public static PropertyCriteria startsWith(String propertyName, String prefix) {
        return new PropertyCriteria(propertyName, 
            value -> value != null && value.startsWith(prefix));
    }
    
    /**
     * Creates a criteria that checks if a property contains a substring.
     */
    public static PropertyCriteria contains(String propertyName, String substring) {
        return new PropertyCriteria(propertyName, 
            value -> value != null && value.contains(substring));
    }
    
    @Override
    public boolean evaluate(Message message) {
        String value = MessageUtils.getStringProperty(message, propertyName);
        return matcher.matches(value);
    }
    
    @FunctionalInterface
    private interface PropertyMatcher {
        boolean matches(String value);
    }
}
