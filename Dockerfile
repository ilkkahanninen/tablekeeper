# --- build ---
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
# Dependencies first, so code changes don't re-download them
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN gradle dependencies --no-daemon -q > /dev/null || true
COPY src ./src
RUN gradle installDist --no-daemon -q

# --- run ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/tablekeeper /app
# Game modules are plain files: mount over /app/modules to edit them without rebuilding
COPY modules /app/modules
ENV MODULES_DIR=/app/modules \
    PORT=8080
EXPOSE 8080
CMD ["/app/bin/tablekeeper"]
