# Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

# Runtime stage — non-root user, minimal JRE image
FROM eclipse-temurin:25-jre
RUN groupadd -r chargeflow && useradd -r -g chargeflow -s /usr/sbin/nologin chargeflow
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER chargeflow
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
