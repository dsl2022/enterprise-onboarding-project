# syntax=docker/dockerfile:1
# Build stage: Maven + JDK 21 (no local Maven required — see ADR-0008).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Cache deps first.
COPY app/pom.xml ./pom.xml
RUN mvn -q -B -e dependency:go-offline
COPY app/src ./src
RUN mvn -q -B -e -DskipTests package

# Runtime stage: slim JRE, non-root.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN useradd -r -u 10001 appuser
COPY --from=build /src/target/enterprise-onboarding-app-0.0.1.jar /app/app.jar
USER appuser:appuser
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
