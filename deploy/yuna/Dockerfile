FROM gradle:8.14.1-jdk21 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle . .
USER gradle
RUN gradle --no-daemon :Lavalink-Server:bootJar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl python3 \
    && curl --fail --location --retry 3 \
       https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp \
       --output /usr/local/bin/yt-dlp \
    && chmod 0755 /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 322 lavalink \
    && useradd --uid 322 --gid lavalink --system --create-home lavalink
WORKDIR /opt/Lavalink
COPY --from=build --chown=lavalink:lavalink /workspace/LavalinkServer/build/libs/Lavalink.jar /opt/Lavalink/Lavalink.jar
USER lavalink
ENTRYPOINT ["java", "-jar", "Lavalink.jar"]
