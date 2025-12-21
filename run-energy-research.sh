#!/bin/bash

# Configuration
DURATION_SEC=${1:-3600}  # Default 1 Hour
RESULTS_DIR="energy-results/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"
mkdir -p results # Ensure the volume mount directory exists
rm -f results/*.json # Clean up previous results

echo "Starting Energy Research (Duration: ${DURATION_SEC}s per test)"
echo "Results will be saved to: $RESULTS_DIR"

# Build the project first
echo "Building project (using Docker)..."
docker compose build

# Function to run a single test scenario
run_scenario() {
    local scenario_name=$1
    local proxy_enabled=$2
    local criteria_type=$3
    local window_ms=$4
    local batch_size=$5

    echo "----------------------------------------------------------------"
    echo "Starting Scenario: $scenario_name"
    echo "Proxy: $proxy_enabled, Criteria: $criteria_type, Window: ${window_ms}ms, Batch: $batch_size"
    echo "----------------------------------------------------------------"

    # Export variables for docker-compose
    export PROXY_ENABLED=$proxy_enabled
    export CRITERIA_TYPE=$criteria_type
    export DURATION_SEC=$DURATION_SEC
    export CONDENSER_WINDOW_MS=$window_ms
    export CONDENSER_BATCH_SIZE=$batch_size
    export MSG_RATE=50 # Keep rate constant

    # Start containers
    docker compose up -d --force-recreate

    echo "Test running for $DURATION_SEC seconds..."
    
    # Wait for producer to finish (it exits after DURATION_SEC)
    PRODUCER_ID=$(docker compose ps -q producer)
    docker wait $PRODUCER_ID > /dev/null

    echo "Producer finished. Stopping consumer..."
    
    docker compose stop consumer
    echo "Waiting for consumer to shutdown..."
    
    echo "Stopping Scenario: $scenario_name"
    
    
    # Move results
    mv results/result_*.json "$RESULTS_DIR/${scenario_name}_metrics.json" 2>/dev/null
    mv results/producer_stats_*.json "$RESULTS_DIR/${scenario_name}_producer_stats.json" 2>/dev/null

    # Cleanup
    docker compose down
    
    echo "Scenario $scenario_name completed."
    sleep 10 # Cool down
}

# --- Test Scenarios ---

# 1. Baseline (No Proxy)
run_scenario "baseline" false "NO_OP" 0 0

# 2. Proxy - Standard Condenser
run_scenario "proxy_condenser_standard" true "CONDENSER" 2000 100

# 3. Proxy - Aggressive Condenser
run_scenario "proxy_condenser_aggressive" true "CONDENSER" 5000 500

# 4. Proxy - Filter Property (High Priority only)
run_scenario "proxy_filter_property" true "FILTER_PROPERTY" 0 0

# 5. Proxy - Filter Content (Important only)
run_scenario "proxy_filter_content" true "FILTER_CONTENT" 0 0

echo "All energy research tests completed. Results in $RESULTS_DIR"
