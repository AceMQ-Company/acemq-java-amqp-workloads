# The studio as an image.
#
#   docker build -t acemq-workloads-studio .
#   docker run --rm -p 8480:8480 acemq-workloads-studio
#
# Two stages, because the build needs Maven, a JDK and Node and the result needs
# none of them: a runtime image carrying a toolchain is several hundred
# megabytes of attack surface for something that only has to run a jar.
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /src

# The poms first, so a change to a source file does not re-resolve every
# dependency. This layer is the slow one and it changes rarely.
COPY pom.xml .
COPY library/pom.xml library/
COPY studio/pom.xml studio/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY library library
COPY studio studio

# Tests are the pipeline's job. Running the integration suite here would need a
# broker inside the build, and an image build that quietly skips them when one
# is missing is worse than one that never claimed to run them.
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre AS runtime

# curl for the health check below. Nothing else is added: every package in a
# runtime image is something that needs patching later.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Not root. The studio writes exactly one file, and it does not need to be
# anybody important to do it.
RUN groupadd --system acemq \
    && useradd --system --gid acemq --home /home/acemq --create-home acemq \
    && mkdir -p /data \
    && chown acemq:acemq /data

COPY --from=build --chown=acemq:acemq /src/studio/target/acemq-workloads-studio.jar /app/studio.jar

USER acemq
WORKDIR /app

# The database on a volume. Without this the run history is inside the
# container's writable layer and disappears with it, which is a surprise the
# first time somebody restarts the thing.
VOLUME ["/data"]
ENV ACEMQ_STUDIO_DATABASE=/data/workloads-studio.db

# No token is baked in. In a container the studio binds to every interface and
# refuses to run without one, so it generates a token and prints it at startup
# unless ACEMQ_STUDIO_TOKEN is passed at run time. A default in the image would
# be a shared secret that is not secret.

# A load generator is the one workload that genuinely wants its heap sized from
# the container's limit rather than from the host's memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8480

# The liveness endpoint is open without a token on purpose: a container that
# will not answer a probe without a secret is a container that gets restarted
# for ever.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fs http://localhost:8480/actuator/health/liveness || exit 1

# exec form, so the JVM is PID 1 and receives SIGTERM directly. Spring shuts
# down gracefully and stops whatever run is going, which is the difference
# between losing a ninety-second measurement and getting a report for it.
ENTRYPOINT ["java", "-jar", "/app/studio.jar"]
