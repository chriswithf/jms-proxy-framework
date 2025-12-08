FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/jms-proxy-examples/target/jms-proxy-examples-*.jar /app/examples.jar
COPY --from=build /app/jms-proxy-examples/target/lib /app/lib

# Default command (can be overridden)
CMD ["java", "-cp", "examples.jar:lib/*", "com.jmsproxy.examples.BenchmarkProducer"]
