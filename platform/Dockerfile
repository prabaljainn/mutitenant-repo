# syntax=docker/dockerfile:1.7
#
# Multi-stage Dockerfile for the Orochiverse platform.
#
#   1. `build`  — compiles + runs the test suite. Cached layer for deps.
#   2. `runtime`— minimal JRE image for production deployment.
#
# Phase 1.1 uses the `build` stage to run tests without requiring a local
# Java install. Phase 1.12 will harden the runtime stage for production.

###############################################################################
# Stage 1: build + test
###############################################################################
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Pre-copy wrapper + pom for dependency caching. If pom.xml is unchanged,
# Maven's local repo (~/.m2) is reused on subsequent builds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# Copy the rest of the sources.
COPY src/ ./src/

# Compile + run unit + integration tests. Failure here fails the image build.
RUN ./mvnw -B -ntp verify

# Produce a layered jar so the runtime stage gets better caching.
RUN ./mvnw -B -ntp -DskipTests=true package && \
    cd target && \
    java -Djarmode=layertools -jar platform-*.jar extract

###############################################################################
# Stage 2: runtime (Phase 1.12 will tune this further)
###############################################################################
FROM eclipse-temurin:25-jre-alpine AS runtime

# Non-root user for defense in depth.
RUN addgroup -S platform && adduser -S -G platform platform

WORKDIR /app
USER platform

# Layered copy maximises Docker layer cache hits across builds.
COPY --from=build /workspace/target/dependencies/ ./
COPY --from=build /workspace/target/spring-boot-loader/ ./
COPY --from=build /workspace/target/snapshot-dependencies/ ./
COPY --from=build /workspace/target/application/ ./

EXPOSE 8080

# JVM flags suited to containerized workloads with virtual threads.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+ZGenerational --enable-preview"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
