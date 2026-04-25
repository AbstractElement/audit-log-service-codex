# syntax=docker/dockerfile:1

FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon clean build

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --create-home --uid 10001 appuser
COPY --from=build /workspace/build/libs/audit-log-service-0.0.1-SNAPSHOT.jar /app/app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
