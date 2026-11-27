FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/delivery.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
