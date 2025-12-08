package com.jmsproxy.core.util;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

/**
 * Utility class for JMS message operations.
 */
public final class MessageUtils {
    
    private MessageUtils() {
        // Utility class
    }
    
    /**
     * Extracts text content from a JMS message.
     * 
     * @param message The JMS message
     * @return The text content or null
     */
    public static String getTextContent(Message message) {
        if (message instanceof TextMessage textMessage) {
            try {
                return textMessage.getText();
            } catch (JMSException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * Safely gets a string property from a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @return The property value or null
     */
    public static String getStringProperty(Message message, String propertyName) {
        try {
            return message.getStringProperty(propertyName);
        } catch (JMSException e) {
            return null;
        }
    }
    
    /**
     * Safely gets an int property from a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @param defaultValue The default value if property doesn't exist
     * @return The property value or default
     */
    public static int getIntProperty(Message message, String propertyName, int defaultValue) {
        try {
            return message.getIntProperty(propertyName);
        } catch (JMSException e) {
            return defaultValue;
        }
    }
    
    /**
     * Safely gets a long property from a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @param defaultValue The default value if property doesn't exist
     * @return The property value or default
     */
    public static long getLongProperty(Message message, String propertyName, long defaultValue) {
        try {
            return message.getLongProperty(propertyName);
        } catch (JMSException e) {
            return defaultValue;
        }
    }
    
    /**
     * Safely gets a boolean property from a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @param defaultValue The default value if property doesn't exist
     * @return The property value or default
     */
    public static boolean getBooleanProperty(Message message, String propertyName, boolean defaultValue) {
        try {
            return message.getBooleanProperty(propertyName);
        } catch (JMSException e) {
            return defaultValue;
        }
    }
    
    /**
     * Checks if a message has a specific property.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @return true if the property exists
     */
    public static boolean hasProperty(Message message, String propertyName) {
        try {
            return message.propertyExists(propertyName);
        } catch (JMSException e) {
            return false;
        }
    }
    
    /**
     * Safely sets a string property on a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @param value The property value
     */
    public static void setStringProperty(Message message, String propertyName, String value) {
        try {
            message.setStringProperty(propertyName, value);
        } catch (JMSException e) {
            // Log and continue
        }
    }
    
    /**
     * Safely sets an int property on a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @param value The property value
     */
    public static void setIntProperty(Message message, String propertyName, int value) {
        try {
            message.setIntProperty(propertyName, value);
        } catch (JMSException e) {
            // Log and continue
        }
    }
    
    /**
     * Safely sets a long property on a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @param value The property value
     */
    public static void setLongProperty(Message message, String propertyName, long value) {
        try {
            message.setLongProperty(propertyName, value);
        } catch (JMSException e) {
            // Log and continue
        }
    }
    
    /**
     * Safely sets a boolean property on a message.
     * 
     * @param message The JMS message
     * @param propertyName The property name
     * @param value The property value
     */
    public static void setBooleanProperty(Message message, String propertyName, boolean value) {
        try {
            message.setBooleanProperty(propertyName, value);
        } catch (JMSException e) {
            // Log and continue
        }
    }
}
