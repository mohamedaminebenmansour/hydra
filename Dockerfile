FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
COPY . .
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw clean package -DskipTests
EXPOSE 8080
CMD java -jar target/*.jar --server.port=${PORT:-8080}