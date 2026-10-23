FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/install/aeronfire /app
ENTRYPOINT ["/app/bin/aeronfire"]
