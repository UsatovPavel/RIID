// Usage:
// ./gradlew allReports for run code quality utils and save report in one file
//  ./gradlew testAll for run all tests
// ./gradlew testStress for run only stress tests
// ./gradlew testLocal for run only local tests
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

checkstyle {
    configFile = file("config/checkstyle/checkstyle.xml")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))//idea say<=1.21 it vulnerable
    testImplementation("org.testcontainers:junit-jupiter")//but this lib only in 1.21 version// TODO: at May check 2.0.3
    testImplementation("org.testcontainers:testcontainers")//this lib has 2.0.3 version
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("commons-codec:commons-codec:1.16.1")
    implementation("commons-io:commons-io:2.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("org.eclipse.jetty:jetty-client:12.1.5")
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

tasks.withType<Test>().configureEach {
    // Always rerun tests by default 
    outputs.upToDateWhen { false }
}

tasks.register<Test>("testStress") {
    group = "verification"
    description = "Run stress-tagged tests"
    useJUnitPlatform {
        includeTags("stress")
    }
}

tasks.register<Test>("testLocal") {
    group = "verification"
    description = "Run local-tagged tests (e.g., Testcontainers registry)"
    useJUnitPlatform {
        includeTags("local")
    }
}

tasks.register("testAll") {
    group = "verification"
    description = "Run default tests and stress tests"
    dependsOn("test", "testStress")
}

tasks.register("allReports") {
    group = "verification"
    description = "Concatenate quality reports into build/reports/all-reports.html"
    dependsOn("check")
    doLast {
        val reports = listOf(
            "checkstyle/main.html",
            "checkstyle/test.html",
            "pmd/main.html",
            "pmd/test.html",
            "spotbugs/main.html",
            "spotbugs/test.html"
        )
        val reportsDir = layout.buildDirectory.dir("reports").get().asFile
        reportsDir.mkdirs()
        val out = reportsDir.resolve("all-reports.html")
        out.writeText("") // clear
        reports.forEach { rel ->
            val f = reportsDir.resolve(rel)
            if (f.exists()) {
                out.appendText(f.readText())
            }
        }
        println("Concatenated report generated at ${out.absolutePath}")
    }
}
//для запуска Docker у тасок вывод некрасивый по сравнению с Makefile
tasks.register<Exec>("dockerBuild") {
    group = "docker"
    description = "Build demo image (riid-demo)"
    commandLine("docker", "build", "-t", "riid-demo", ".")
}

val dockerBuildTestImage = tasks.register<Exec>("dockerBuildTestImage") {
    group = "docker"
    description = "Build test image (builder target)"
    commandLine(
        "docker", "build",
        "--target", "builder",
        "-t", "riid-test", "."
    )
}

val dockerRunTestsInContainer = tasks.register<Exec>("dockerRunTestsInContainer") {
    group = "docker"
    description = "Run gradlew test inside riid-test container"
    dependsOn(dockerBuildTestImage)
    commandLine(
        "docker", "run", "--rm",
        "-v", "gradle-cache:/root/.gradle",
        "riid-test",
        "./gradlew", "test", "-PdisableLocal"
    )
}

tasks.register("dockerTest") {
    group = "docker"
    description = "Build test image and run tests inside it"
    dependsOn(dockerRunTestsInContainer)
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "riid.app.Main")
    }
}