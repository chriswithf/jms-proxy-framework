#!/bin/bash
echo "Building Docker images..."
docker compose build producer

echo "Running Comprehensive Benchmark..."
# Run the benchmark class inside the container, overriding the default command
docker compose run --rm producer java -cp examples.jar:lib/* com.jmsproxy.examples.ComprehensiveBenchmark
