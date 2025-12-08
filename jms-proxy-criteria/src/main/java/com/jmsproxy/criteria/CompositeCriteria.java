package com.jmsproxy.criteria;

import com.jmsproxy.core.SendCriteria;
import jakarta.jms.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Composite criteria that combines multiple criteria with logical operators.
 */
public class CompositeCriteria implements SendCriteria {
    
    private final List<SendCriteria> criteria;
    private final LogicalOperator operator;
    
    private CompositeCriteria(List<SendCriteria> criteria, LogicalOperator operator) {
        this.criteria = new ArrayList<>(criteria);
        this.operator = operator;
    }
    
    /**
     * Creates a criteria that requires ALL sub-criteria to pass.
     */
    public static CompositeCriteria all(SendCriteria... criteria) {
        return new CompositeCriteria(Arrays.asList(criteria), LogicalOperator.AND);
    }
    
    /**
     * Creates a criteria that requires ANY sub-criteria to pass.
     */
    public static CompositeCriteria any(SendCriteria... criteria) {
        return new CompositeCriteria(Arrays.asList(criteria), LogicalOperator.OR);
    }
    
    /**
     * Creates a criteria that requires NONE of the sub-criteria to pass.
     */
    public static CompositeCriteria none(SendCriteria... criteria) {
        return new CompositeCriteria(Arrays.asList(criteria), LogicalOperator.NONE);
    }
    
    /**
     * Creates a builder for complex criteria combinations.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public boolean evaluate(Message message) {
        return switch (operator) {
            case AND -> criteria.stream().allMatch(c -> c.evaluate(message));
            case OR -> criteria.stream().anyMatch(c -> c.evaluate(message));
            case NONE -> criteria.stream().noneMatch(c -> c.evaluate(message));
        };
    }
    
    private enum LogicalOperator {
        AND, OR, NONE
    }
    
    public static class Builder {
        private final List<SendCriteria> criteria = new ArrayList<>();
        private LogicalOperator operator = LogicalOperator.AND;
        
        public Builder add(SendCriteria criterion) {
            criteria.add(criterion);
            return this;
        }
        
        public Builder addAll(SendCriteria... criteria) {
            this.criteria.addAll(Arrays.asList(criteria));
            return this;
        }
        
        public Builder withAnd() {
            this.operator = LogicalOperator.AND;
            return this;
        }
        
        public Builder withOr() {
            this.operator = LogicalOperator.OR;
            return this;
        }
        
        public Builder withNone() {
            this.operator = LogicalOperator.NONE;
            return this;
        }
        
        public CompositeCriteria build() {
            return new CompositeCriteria(criteria, operator);
        }
    }
}
