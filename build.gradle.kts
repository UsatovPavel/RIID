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

val javaVersion: Int = if (project.hasProperty("javaVersion")) (project.property("javaVersion") as String).toInt() else 23
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
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
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
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
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
    reports.create("html") {
        required.set(true)   // всегда генерить html-отчёт
    }
}

tasks.jacocoTestReport {
    enabled = !skipQuality
    dependsOn(tasks.test) // jacoco runs only when explicitly invoked, but needs tests
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

fun registerModuleTest(name: String, pattern: String, descriptionText: String) {
    tasks.register<Test>(name) {
        group = "verification"
        description = descriptionText
        dependsOn(tasks.testClasses)
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching("riid.${pattern}.*")
        }
    }
}

registerModuleTest(
    name = "testApp",
    pattern = "app",
    descriptionText = "Run tests under riid.app"
)

registerModuleTest (
    name = "testConfig",
    pattern = "config",
    descriptionText = "Run tests under riid.config"
)

registerModuleTest(
    name = "testClient",
    pattern = "client",
    descriptionText = "Run tests under riid.client"
)

registerModuleTest(
    name = "testDispatcher",
    pattern = "dispatcher",
    descriptionText = "Run tests under riid.dispatcher"
)

registerModuleTest(
    name = "testRuntime",
    pattern = "runtime",
    descriptionText = "Run tests under riid.runtime"
)

tasks.register("allReports") {
    group = "verification"
    description = "Concatenate quality reports into build/reports/all-reports.html (best effort, no dependsOn check)"
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
        val header = """
            <html>
            <head>
              <meta charset="UTF-8">
              <title>Riid combined reports</title>
              <style>
                body { font-family: Arial, sans-serif; margin: 1.5rem; }
                h1 { margin-bottom: 0.5rem; }
                h2 { margin-top: 2rem; }
                .missing { color: #888; }
                .section { border-top: 1px solid #ccc; padding-top: 1rem; }
              </style>
            </head>
            <body>
            <h1>Combined quality reports</h1>
        """.trimIndent()
        val footer = """
            </body>
            </html>
        """.trimIndent()

        fun extractBody(html: String): String {
            val bodyRegex = Regex("(?is)<body[^>]*>(.*)</body>")
            val body = bodyRegex.find(html)?.groups?.get(1)?.value ?: html
            val headRegex = Regex("(?is)<head[^>]*>.*?</head>")
            return headRegex.replace(body, "")
        }

        val content = buildString {
            append(header)
            reports.forEach { rel ->
                val f = reportsDir.resolve(rel)
                append("""<div class="section">""")
                append("<h2>${rel}</h2>")
                if (f.exists()) {
                    append(extractBody(f.readText()))
                } else {
                    append("""<p class="missing">Report not found: $rel</p>""")
                }
                append("</div>")
            }
            append(footer)
        }
        out.writeText(content)
        println("Concatenated report generated at ${out.absolutePath}")
    }
}

// Always attempt concatenation after check (even if check fails)
// Detach tests from check: run tests/jacoco explicitly when needed
tasks.named("check") {
    // Explicit quality-only dependencies; tests are not part of check
    setDependsOn(
        listOf(
            tasks.named("checkstyleMain"),
            tasks.named("checkstyleTest"),
            tasks.named("pmdMain"),
            tasks.named("pmdTest"),
            tasks.named("spotbugsMain"),
            tasks.named("spotbugsTest")
        )
    )
    finalizedBy("allReports")
}

// Ensure allReports runs after individual quality tasks too (even on failure)
listOf(
    "pmdMain",
    "pmdTest",
    "spotbugsMain",
    "spotbugsTest",
    "checkstyleMain",
    "checkstyleTest"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        finalizedBy("allReports")
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
        attributes("Main-Class" to "riid.app.ImageLoadServiceFactory")
    }
}