FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY . ./
RUN chmod +x mvnw && ./mvnw -B package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 spring

COPY --from=build /workspace/target/*.jar /app/app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
