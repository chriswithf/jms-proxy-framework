#!/bin/bash
mvn clean install -DskipTests
mvn -pl jms-proxy-examples exec:java -Dexec.mainClass="com.jmsproxy.examples.ComprehensiveBenchmark"
