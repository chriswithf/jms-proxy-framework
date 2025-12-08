#!/bin/bash
echo "Building project..."
mvn clean package -DskipTests

echo "Building Docker images..."
docker-compose build
