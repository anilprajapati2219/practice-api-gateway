FROM eclipse-temurin:17.0.17_10-jre-alpine-3.23

WORKDIR /app

COPY target/practice-dashboard-gateway-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java","-jar","/app/app.jar"]