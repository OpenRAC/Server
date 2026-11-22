# The hub and the game server in one image: built with the JDK, run on the JRE alone.
#   docker build -t openrac-server .
#   docker run --rm -p 7770:7770/tcp -p 7770:7770/udp -p 7771:7771/tcp openrac-server          # a game server
#   docker run --rm -p 7177:7177/tcp openrac-server hub.jar                                     # the hub
# Settings: .env.defaults is built in; mount a .env at /app/.env, pass -e KEY=value or --key value.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# The wrapper and build scripts first, so a source change keeps Gradle's download cached.
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY build-logic build-logic
COPY services/common/build.gradle.kts services/common/
COPY services/hub/build.gradle.kts services/hub/
COPY services/server/build.gradle.kts services/server/
RUN ./gradlew --no-daemon --quiet help

COPY services services
RUN ./gradlew --no-daemon --quiet :hub:buildHubJar :server:buildServerJar

FROM eclipse-temurin:25-jre
WORKDIR /app

# Neither needs privileges: they listen on their ports and write only the data folder.
RUN useradd --system --no-create-home openrac && mkdir data && chown openrac data

COPY --from=build /src/services/hub/build/libs/openrac-hub-1.0.0.jar hub.jar
COPY --from=build /src/services/server/build/libs/openrac-server-1.0.0.jar server.jar
COPY .env.defaults .

USER openrac
EXPOSE 7177/tcp 7770/tcp 7770/udp 7771/tcp

# ZGC keeps pauses under a millisecond. Compact headers shrink every object.
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+UseCompactObjectHeaders", "-jar"]
CMD ["server.jar"]
