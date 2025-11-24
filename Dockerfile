FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd -r fibgroup && useradd -r -g fibgroup fibuser
RUN chown -R fibuser:fibgroup /app
USER fibuser

COPY --from=builder --chown=fibuser:fibgroup /app/target/untitled1-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT exec java -jar app.jar