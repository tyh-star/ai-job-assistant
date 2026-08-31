FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package \
    && find target -maxdepth 1 -name 'aijob-*.jar' ! -name '*sources*' ! -name '*javadoc*' -exec cp {} /tmp/app.jar \;

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        libreoffice-writer \
        fonts-liberation \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/uploads /app
WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
ENV AIJOB_STORAGE_ROOT=/data/uploads \
    PDF_EXPORT_SOFFICE_PATH=/usr/bin/soffice \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
