#!/bin/bash
echo "Running BASELINE test (No Proxy)..."
export PROXY_ENABLED=false
export MSG_RATE=50
export DURATION_SEC=60

# Start in detached mode
docker-compose up -d --force-recreate

echo "Test running... Tailing logs..."
# Tail logs in background
docker-compose logs -f producer consumer &
LOG_PID=$!

# Wait for producer to finish
PRODUCER_ID=$(docker-compose ps -q producer)
docker wait $PRODUCER_ID > /dev/null

echo "Producer finished. Stopping consumer to generate report..."
docker-compose stop consumer
sleep 5 # Wait for report to flush

# Stop tailing logs
kill $LOG_PID 2>/dev/null

echo ""
echo "----------------------------------------------------------------"
echo "Test Finished!"
echo "----------------------------------------------------------------"
docker-compose down
