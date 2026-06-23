FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app
RUN addgroup --system arkmem && adduser --system --ingroup arkmem arkmem
COPY --from=build /workspace/target/arkmem-*.jar /app/arkmem.jar
USER arkmem

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/arkmem.jar"]
