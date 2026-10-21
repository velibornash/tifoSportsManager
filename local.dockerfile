# syntax=docker/dockerfile:1
#
# The local Docker build (owner, 2026-10-21: "napravi da pokrecem lokalno iz dockera").
#
# WHY A SECOND FILE, when image.dockerfile already builds the same jar: **image.dockerfile is the
# server's build**, and the server's build is not this line's to touch. The two differ in one way
# that matters here and not there — the Maven repository is a build cache, so a rebuild after a code
# change does not download the world again.
#
# BuildKit is required for the cache mount; Docker Desktop uses it by default.

FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Git is already in this image (2.34), and it is here for one reason: the build writes the commit
# into the jar, so the Settings page can say which code is running. Without git in the build stage
# the jar would answer "unknown" for its version, and the one thing the version is for — telling two
# builds apart — would be gone. Nothing is installed for it: the image has it.

COPY . .

# The cache mount is the difference between a two-minute rebuild and a ten-minute one.
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests

# Runtime stage: only the JDK, only the jar.
FROM eclipse-temurin:21-jdk
WORKDIR /app

# postgresql-client-18 is pinned deliberately, and for the same reason as in image.dockerfile: the
# app's backup runs pg_dump against the database, and pg_dump refuses to run against a server of a
# newer major version than itself. 18 is the version of the database in this compose file.
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client-18 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

# The owner's world default tactic lives in var/tactics-editor-profiles.json - the one copy that
# survives a Reset DB, because the Reset empties the database and the file is what the rebuild
# reads. The path is relative to the working directory, so it must land at /app/var.
COPY --from=build /app/var ./var

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
