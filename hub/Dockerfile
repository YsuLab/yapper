# Yapper hub. Build context = the repository root (it needs the Gradle wrapper):
#   docker compose -f hub/deploy/docker-compose.yml up -d --build
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY hub ./hub
RUN chmod +x gradlew && ./gradlew --no-daemon -p hub bootJar -Dorg.gradle.jvmargs=-Xmx512m

FROM eclipse-temurin:21-jre-noble
# sqlite3 is only for looking at or restoring a backup by hand.
# Runs as root inside the container so the ./data bind mount works with no chown on the host.
# The container publishes no port itself; Dokploy (Traefik) routes HTTPS to port 8080.
RUN apt-get update && apt-get install -y --no-install-recommends sqlite3 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/hub/build/libs/yapper-hub.jar /app/yapper-hub.jar
ENV HUB_DB=/data/hub.db HUB_BACKUP_DIR=/data/backups \
    JAVA_OPTS="-Xmx256m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/yapper-hub.jar"]
