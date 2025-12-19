#!/bin/bash

# Configuration
ITERATIONS=5
DURATION=3600 # 1 hour per scenario (Total runtime approx 25 hours)

echo "================================================================"
echo "Starting Repeated Energy Research Campaign"
echo "Total Iterations: $ITERATIONS"
echo "Duration per Scenario: $DURATION seconds"
echo "Total Scenarios per Iteration: 5"
echo "Estimated Total Runtime: $((ITERATIONS * 5)) hours"
echo "================================================================"

for ((i=1; i<=ITERATIONS; i++)); do
    echo ""
    echo "################################################################"
    echo "STARTING CAMPAIGN ITERATION $i OF $ITERATIONS"
    echo "Start Time: $(date)"
    echo "################################################################"
    echo ""
    
    # Run the main research script
    ./run-energy-research.sh $DURATION
    
    echo ""
    echo "Iteration $i completed at $(date)."
    
    if [ $i -lt $ITERATIONS ]; then
        echo "Cooling down system for 60 seconds before next iteration..."
        sleep 60
    fi
done

echo ""
echo "================================================================"
echo "All $ITERATIONS iterations completed successfully."
echo "End Time: $(date)"
echo "================================================================"
