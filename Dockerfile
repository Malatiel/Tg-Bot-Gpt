FROM maven:3.9.9-eclipse-temurin-22 AS build

WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd
COPY src src
RUN chmod +x mvnw && MAVEN_CONFIG= ./mvnw -q -DskipTests package

FROM eclipse-temurin:22-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/Tg-Bot-Gpt-*.jar app.jar

# JVM honors container memory limits and uses ~75% for heap. Override
# JAVA_TOOL_OPTIONS at runtime to tune (e.g. -XX:MaxRAMPercentage=50 on a tiny VM).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
