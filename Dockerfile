# Runtime image of the MotoFlow backend.
# CI builds and tests the JAR first (./mvnw verify) and builds this image from that same JAR,
# so there is no Maven stage here. Local build: ./mvnw -DskipTests package && docker build -t motoflow-backend .
FROM eclipse-temurin:24-jre

WORKDIR /app
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home app

COPY target/*.jar app.jar

USER 10001:10001
EXPOSE 8080
# Size the heap from the container memory limit; exit on OOM so Docker restarts the container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Exec form so the JVM receives SIGTERM and Spring Boot shuts down gracefully.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
