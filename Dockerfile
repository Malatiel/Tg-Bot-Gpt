#FROM openjdk:22-jdk-slim
#VOLUME /tmp
#COPY target/Tg-Bot-Gpt-0.0.1-SNAPSHOT.jar app.jar
#ENTRYPOINT ["java", "-jar", "/app.jar"]


# Используем официальный образ OpenJDK
FROM openjdk:22-jdk-slim

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем jar-файл приложения
COPY target/Tg-Bot-Gpt-0.0.1-SNAPSHOT.jar app.jar

# Указываем команду для запуска приложения
ENTRYPOINT ["java", "-jar", "app.jar"]