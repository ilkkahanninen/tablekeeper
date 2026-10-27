# --- build ---
FROM gradle:8.14-jdk21 AS build
# A Java runtime with just the modules the app uses (~65 MB instead of the full JRE's 158). A dependency that needs
# another one fails at runtime with NoClassDefFoundError for a java.* or jdk.* class: add its module here.
# jdk.zipfs: Playwright unpacks its driver through a zip file system; jdk.crypto.ec, jdk.charsets: TLS and encodings.
RUN jlink --add-modules java.base,java.desktop,java.sql,java.naming,java.management,java.logging,java.xml,java.security.jgss,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.charsets \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /jre
WORKDIR /src
# Dependencies first, so code changes don't re-download them
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN gradle dependencies --no-daemon -q > /dev/null || true
COPY src ./src
RUN gradle installDist --no-daemon -q
# Just the Playwright jars, for installing Chromium below: unchanged by code changes, so that step stays cached
RUN mkdir /playwright && cp build/install/tablekeeper/lib/playwright-*.jar build/install/tablekeeper/lib/driver-*.jar \
    build/install/tablekeeper/lib/gson-*.jar /playwright/
# Playwright's driver jar carries Node for five platforms (204 MB); the app's copy keeps only this image's (~44 MB)
ARG TARGETARCH
RUN keep=$([ "$TARGETARCH" = "arm64" ] && echo linux-arm64 || echo linux) \
    && bundle=$(ls /src/build/install/tablekeeper/lib/driver-bundle-*.jar) \
    && mkdir /tmp/bundle && cd /tmp/bundle && jar xf "$bundle" \
    && test -d "driver/$keep" \
    && for d in driver/*/; do [ "$d" = "driver/$keep/" ] || rm -rf "$d"; done \
    && rm "$bundle" && jar cfM "$bundle" -C /tmp/bundle . && rm -rf /tmp/bundle

# --- run ---
FROM ubuntu:26.04
ENV JAVA_HOME=/opt/java \
    LANG=C.UTF-8 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PATH=$JAVA_HOME/bin:$PATH
COPY --from=build /jre /opt/java
WORKDIR /app
# Chromium for PDFs (print/Chromium.kt): the headless shell and the system libraries it needs, installed with just the
# Playwright jars (mounted, not copied, so they don't stay in a layer). Then, in the same layer so they're really gone:
# - the fallback fonts its dependencies bring (CJK, Unifont, emoji…, ~90 MB): PDFs embed their own fonts, and DejaVu and
#   Liberation stay as the last resort (characters none of them has print as boxes);
# - Mesa and LLVM (~180 MB), which libgbm drags in but the headless shell never uses. That leaves dpkg's dependencies
#   unmet, so it comes after apt is done; the PDF check below fails the build if Chromium ever starts needing them.
RUN --mount=type=bind,from=build,source=/playwright,target=/playwright \
    java -cp "/playwright/*" com.microsoft.playwright.CLI install --with-deps --only-shell chromium \
    && apt-get purge -y $(dpkg-query -Wf '${Package}\n' | grep -E '^x?fonts-' | grep -vxE 'fonts-(liberation|dejavu-core)') \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* \
    && gpu=$(dpkg-query -Wf '${Package}\n' | grep -E '^(mesa-libgallium|libllvm[0-9]+|libgl1-mesa-dri|libglx-mesa0|mesa-vulkan-drivers)$' || true) \
    && { [ -z "$gpu" ] || dpkg --purge --force-depends $gpu; } \
    && echo '<p>Tablekeeper</p>' > /tmp/check.html \
    && "$(ls -d $PLAYWRIGHT_BROWSERS_PATH/chromium_headless_shell-*/*/chrome-headless-shell)" --no-sandbox \
        --print-to-pdf=/tmp/check.pdf file:///tmp/check.html \
    && test -s /tmp/check.pdf && rm /tmp/check.*
COPY --from=build /src/build/install/tablekeeper /app
# Game modules are plain files: mount over /app/modules to edit them without rebuilding
COPY modules /app/modules
ENV MODULES_DIR=/app/modules \
    PORT=8080
EXPOSE 8080
CMD ["/app/bin/tablekeeper"]
