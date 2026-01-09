plugins {
    id("java")
    id("checkstyle")
    id("pmd")
    id("jacoco")
    id("com.github.spotbugs") version "6.4.8"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "hse.ru"
version = "0.1-PROTOTYPE"

val javaVersion: Int = if (project.hasProperty("javaVersion")) (project.property("javaVersion") as String).toInt() else 25
val skipQuality: Boolean = project.hasProperty("skipQuality")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("commons-codec:commons-codec:1.16.1")
    implementation("commons-io:commons-io:2.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
}

tasks.test {
    useJUnitPlatform {
        if (!project.hasProperty("includeStress")) {
            excludeTags("stress")
        }
        if (project.hasProperty("disableLocal")) {
            excludeTags("local")
        }
        if (skipQuality) {
            excludeTags("archunit")
        }
    }
}

tasks.withType<Checkstyle>().configureEach {
    enabled = !skipQuality
}

tasks.withType<Pmd>().configureEach {
    enabled = !skipQuality
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    enabled = !skipQuality
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    enabled = !skipQuality
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "riid.app.Main")
    }
}