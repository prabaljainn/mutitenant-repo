# syntax=docker/dockerfile:1.7
#
# Multi-stage Dockerfile for the Orochiverse platform.
#
#   1. `build`   — compiles, runs unit tests, layers the fat jar.
#   2. `runtime` — minimal JRE image, non-root, healthchecked.
#
# Build:
#   docker build -t orochiverse/platform:dev platform/
#
# Run (assumes Mongo on host):
#   docker run --rm -p 8080:8080 \
#       -e SPRING_DATA_MONGODB_URI="mongodb://host.docker.internal:27017/iam_db?replicaSet=rs0" \
#       orochiverse/platform:dev

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

# Compile + run unit tests. Failsafe ITs are NOT run here — they need the
# dev Mongo + MailHog stack which the build container can't provide. CI
# (.github/workflows/build.yml) runs them via the @EnabledIf gate, which
# skips them gracefully when Mongo is unreachable.
#
# SKIP_TESTS=true is the escape hatch used by .github/workflows/quick-push.yml
# for ad-hoc infra-debugging deploys. release.yml never passes it, so the
# normal release path still test-gates inside the image.
ARG SKIP_TESTS=false
RUN if [ "$SKIP_TESTS" = "true" ]; then \
        echo "SKIP_TESTS=true → bypassing ./mvnw test"; \
    else \
        ./mvnw -B -ntp test; \
    fi

# Produce a layered jar so the runtime stage gets better caching.
RUN ./mvnw -B -ntp -DskipTests package && \
    cd target && \
    java -Djarmode=layertools -jar platform-*.jar extract

###############################################################################
# Stage 2: runtime
###############################################################################
FROM eclipse-temurin:25-jre-alpine AS runtime

# wget is the smallest tool that handles HTTP for the HEALTHCHECK; busybox
# wget is already in alpine but pinning it explicitly documents intent.
RUN apk add --no-cache curl

# Non-root user for defense in depth. UID/GID picked outside the typical
# host range to make file-ownership accidents on bind mounts obvious.
RUN addgroup -S -g 10001 platform && adduser -S -u 10001 -G platform platform

WORKDIR /app

# Layered copy — each Boot layer is its own COPY so a code-only change
# only invalidates the small `application/` layer, not the multi-MB
# `dependencies/` one. --chown ensures the platform user owns the files
# without needing a runtime chown.
COPY --from=build --chown=platform:platform /workspace/target/dependencies/ ./
COPY --from=build --chown=platform:platform /workspace/target/spring-boot-loader/ ./
COPY --from=build --chown=platform:platform /workspace/target/snapshot-dependencies/ ./
COPY --from=build --chown=platform:platform /workspace/target/application/ ./

USER platform

EXPOSE 8080

# JVM flags suited to containerized workloads. ZGC + generational mode is
# the default for low-pause services on JDK 25; MaxRAMPercentage lets the
# container's cgroup limit drive heap sizing without a fragile hard-coded
# -Xmx. Override JAVA_OPTS at run time to add e.g. JFR, debugger, or a
# different GC.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+ZGenerational"

# Spring Boot's actuator /actuator/health endpoint is wired up by the
# included spring-boot-starter-actuator. The healthcheck runs every 30s
# after a 60s startup grace — Spring Boot startup on a cold container
# typically lands in ~15s but the extra slack absorbs Mongock's index
# bootstrap on first run against a fresh database.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
