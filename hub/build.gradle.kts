// The hub: tasks, raw captures and drawing pages for Yapper and the Today board.
// Its own Gradle build (not part of the Android build). Run from the repo root:
//   ./gradlew -p hub test        unit + API tests
//   ./gradlew -p hub bootJar     build/libs/yapper-hub.jar
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ysulab.yapper"
version = "1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName.set("yapper-hub.jar")
}

tasks.jar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
}
