FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:25-jre
# iproute2 gives `tc`, which the demo panel uses to add delay and loss to a member's network.
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/install/aeronfire /app
ENTRYPOINT ["/app/bin/aeronfire"]
