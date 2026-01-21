# syntax=docker/dockerfile:1.4
## Builder stage (uses JDK 25)
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

# Copy only the files needed to build first to maximize layer caching
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
COPY README* ./

# Use BuildKit cache mounts to persist Gradle and wrapper caches between builds.

RUN --mount=type=cache,id=gradle-cache,target=/root/.gradle \
    --mount=type=cache,id=gradle-wrapper,target=/workspace/.gradle/wrapper \
    chmod +x ./gradlew && ./gradlew shadowJar -x test --no-daemon

## Runtime stage
FROM eclipse-temurin:25-jdk-jammy
WORKDIR /app

# Copy produced shadow jar
COPY --from=builder /workspace/build/libs/*.jar /app/riid.jar

ENV RIID_REPO=library/busybox
ENV RIID_REF=latest

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/riid.jar"]
