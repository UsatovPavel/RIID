plugins {
    id("java")
    id("checkstyle")
    id("pmd")
    id("jacoco")
    id("com.github.spotbugs")
    id("com.gradleup.shadow") version "9.3.0"
    id("idea")
    id("java-test-fixtures")
    id("riid.code-quality")
}

group = "hse.ru"
version = "0.1-PROTOTYPE"

val javaVersion: Int = if (project.hasProperty("javaVersion")) (project.property("javaVersion") as String).toInt() else 23

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

repositories {
    mavenCentral()
}

apply(from = "gradle/test-source-sets.gradle.kts")
apply(from = "gradle/dependencies.gradle.kts")
apply(from = "gradle/tests-tasks.gradle.kts")
apply(from = "gradle/docker.gradle.kts")

idea {
    module {
        testSources.from(
            "src/test/integration/java",
            "src/test/performance/java",
            "src/test/moduled/java",
            "src/testFixtures/java",
        )
        testResources.from(
            "src/test/integration/resources",
            "src/test/performance/resources",
            "src/test/moduled/resources",
            "src/testFixtures/resources",
        )
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "riid.app.RiidCli")
    }
}