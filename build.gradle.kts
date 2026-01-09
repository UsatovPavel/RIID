plugins {
    id("java")
    id("com.gradleup.shadow") version "9.3.0"
}

group = "hse.ru"
version = "0.1-PROTOTYPE"

val javaVersion: Int = if (project.hasProperty("javaVersion")) (project.property("javaVersion") as String).toInt() else 25

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
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    implementation("commons-codec:commons-codec:1.16.1")
    implementation("commons-io:commons-io:2.21.0")
}

tasks.test {
    useJUnitPlatform {
        if (!project.hasProperty("includeStress")) {
            excludeTags("stress")
        }
        if (project.hasProperty("disableLocal")) {
            excludeTags("local")
        }
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "riid.app.Main")
    }
}