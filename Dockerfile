FROM maven:3.9.9-eclipse-temurin-22 AS build

WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd
COPY src src
RUN chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:22-jre
WORKDIR /app
COPY --from=build /workspace/target/Tg-Bot-Gpt-*.jar app.jar

# JVM honors container memory limits and uses ~75% for heap. Override
# JAVA_TOOL_OPTIONS at runtime to tune (e.g. -XX:MaxRAMPercentage=50 on a tiny VM).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "app.jar"]
