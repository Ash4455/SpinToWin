# syntax=docker/dockerfile:1
# Build context 是 backend/（multi-module：會一起建置 common 等相依模組）：
#   docker build -f gateway/Dockerfile backend
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    sh ./mvnw -B -ntp -pl gateway -am package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --system spring && useradd --system --gid spring spring
WORKDIR /app
COPY --from=build /workspace/gateway/target/gateway-*.jar app.jar
USER spring
ENV SPRING_PROFILES_ACTIVE=docker
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
