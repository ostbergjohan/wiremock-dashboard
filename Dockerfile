FROM eclipse-temurin:21-jre

# Copy the application JAR
COPY target/wiremock-dashboard-0.0.1-jar-with-dependencies.jar /app/wiremock.jar

# Create mappings directory (mount your stubs here at runtime)
RUN mkdir -p /app/wiremock/mappings

WORKDIR /app

# Port 8080: WireMock + Prometheus metrics (configurable via PORT env var)
EXPOSE 8080

CMD ["java", "-jar", "/app/wiremock.jar"]