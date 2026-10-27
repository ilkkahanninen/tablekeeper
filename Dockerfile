# --- build ---
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
# Dependencies first, so code changes don't re-download them
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN gradle dependencies --no-daemon -q > /dev/null || true
COPY src ./src
RUN gradle installDist --no-daemon -q
# Just the Playwright jars, for installing Chromium below: unchanged by code changes, so that step stays cached
RUN mkdir /playwright && cp build/install/tablekeeper/lib/playwright-*.jar build/install/tablekeeper/lib/driver-*.jar \
    build/install/tablekeeper/lib/gson-*.jar /playwright/

# --- run ---
FROM eclipse-temurin:21-jre
WORKDIR /app
# Chromium for PDFs (print/Chromium.kt): the headless shell and the system libraries it needs. Installed with just the
# Playwright jars (mounted, not copied, so they don't stay in a layer), so code changes don't reinstall it.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN --mount=type=bind,from=build,source=/playwright,target=/playwright \
    java -cp "/playwright/*" com.microsoft.playwright.CLI install --with-deps --only-shell chromium \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/build/install/tablekeeper /app
# Game modules are plain files: mount over /app/modules to edit them without rebuilding
COPY modules /app/modules
ENV MODULES_DIR=/app/modules \
    PORT=8080
EXPOSE 8080
CMD ["/app/bin/tablekeeper"]
