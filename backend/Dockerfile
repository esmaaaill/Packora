# ─────────────────────────────────────────────────────────────
# Stage 1 – BUILD
# Use a full JDK image to compile and package the Spring Boot app
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /app

# Copy Maven wrapper and pom.xml first (layer-cache for dependencies)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Grant execute permission to the Maven wrapper
RUN chmod +x ./mvnw

# Download all dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B

# Copy source code and build the fat JAR (skip tests for speed)
COPY src ./src
RUN ./mvnw package -DskipTests -B

# ─────────────────────────────────────────────────────────────
# Stage 2 – RUN
# Use a slim JRE image — no compiler needed at runtime
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS run

WORKDIR /app

# Create an unprivileged user for security
RUN addgroup -S packora && adduser -S packora -G packora

# Copy only the packaged JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Create uploads directory and set permissions
RUN mkdir -p /app/uploads && chown -R packora:packora /app

USER packora

# Expose the port defined in application.properties
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
