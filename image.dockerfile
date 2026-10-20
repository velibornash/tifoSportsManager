# Build stage: koristi Maven + Java 21
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Runtime stage: koristi samo JDK za manju sliku
FROM eclipse-temurin:21-jdk
WORKDIR /app

# postgresql-client-18 is pinned deliberately. The app's backup runs pg_dump
# against the database, and pg_dump refuses to run against a server of a newer
# major version than itself. Installing the unpinned `postgresql-client` would
# follow the base image forward, so a future temurin bump to 19 would silently
# break backups again. 18.6 matches the server.
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client-18 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]