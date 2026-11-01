# The server in a container: built with the JDK, run on the JRE alone.
#   docker build -t openrac-server .
#   docker run --rm -p 7770:7770/tcp -p 7770:7770/udp -p 7771:7771/tcp openrac-server
# Settings: .env.defaults is built in; mount a .env at /app/.env or pass --key value pairs.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# The wrapper and build scripts first, so a source change keeps Gradle's download cached.
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY build-logic build-logic
COPY services/common/build.gradle.kts services/common/
COPY services/server/build.gradle.kts services/server/
RUN ./gradlew --no-daemon --quiet help

COPY services services
RUN ./gradlew --no-daemon --quiet :server:buildServerJar

FROM eclipse-temurin:25-jre
WORKDIR /app

# The server needs no privileges: it listens on its ports and writes only its data folder.
RUN useradd --system --no-create-home openrac && mkdir data && chown openrac data

COPY --from=build /src/services/server/build/libs/openrac-server-1.0.0.jar server.jar
COPY .env.defaults .

USER openrac
EXPOSE 7770/tcp 7770/udp 7771/tcp

# ZGC keeps pauses under a millisecond. Compact headers shrink every object.
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+UseCompactObjectHeaders", "-jar", "server.jar"]
