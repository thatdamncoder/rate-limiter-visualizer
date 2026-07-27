# ---------- Build stage ----------
# JDK 25 to match <java.version>25</java.version> in pom.xml
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------- Runtime stage ----------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
# RL_PROFILE + REDIS_HOST are provided by docker-compose
ENTRYPOINT ["java", "-jar", "app.jar"]
