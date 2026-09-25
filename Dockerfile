# Inside Joke: one image with the backend and the built frontend inside.
#   docker build -t inside-joke .
#   docker run --env-file .env -p 8080:8080 inside-joke
# Every setting is an environment variable (.env.example). Outside the "local" profile the server refuses to start
# without DATABASE_PASSWORD and with MAIL_PROVIDER=log.

# ---- build: JDK 21 and Node.js 22 (the frontend module calls npm), tests are run by CI, not here
FROM node:22-bookworm-slim AS node

FROM eclipse-temurin:21-jdk AS build
COPY --from=node /usr/local/ /usr/local/
WORKDIR /src
COPY . .
RUN ./mvnw -B -DskipTests package

# ---- runtime: JRE only, a non-root user
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/backend/target/inside-joke.jar app.jar
USER app
EXPOSE 8080
# The container's memory limit, not the host's, sizes the heap.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Health check for the orchestrator: GET /actuator/health.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
