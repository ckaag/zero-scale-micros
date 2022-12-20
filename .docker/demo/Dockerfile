FROM eclipse-temurin:17-jdk-alpine as build
WORKDIR /workspace/app

COPY ./src ./src
COPY ./gradle ./gradle
COPY ./gradlew ./gradlew
COPY ./build.gradle ./build.gradle
COPY ./settings.gradle ./settings.gradle

RUN chmod +x ./gradlew && ./gradlew build -x test
RUN mkdir -p build/libs/dependency && (cd build/libs/dependency; jar -xf ../*-SNAPSHOT.jar)
RUN ls -la build/libs/dependency

FROM eclipse-temurin:17-jdk-alpine
VOLUME /tmp
COPY --from=build /workspace/app/build/libs/dependency/BOOT-INF/lib /app/lib
COPY --from=build /workspace/app/build/libs/dependency/META-INF /app/META-INF
COPY --from=build /workspace/app/build/libs/dependency/BOOT-INF/classes /app
ENTRYPOINT ["java","-cp","app:app/lib/*","com.example.demo.DemoApplication"]
