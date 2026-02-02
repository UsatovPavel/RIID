import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.the

val sourceSets = the<SourceSetContainer>()
val skipQuality = project.hasProperty("skipQuality")

tasks.named<Test>("test") {
    useJUnitPlatform {
        if (!project.hasProperty("includeStress")) {
            excludeTags("stress")
        }
        excludeTags("porto")
        if (project.hasProperty("disableLocal")) {
            excludeTags("local")
        }
        if (skipQuality) {
            excludeTags("archunit")
        }
    }
    dependsOn("integrationTest", "moduledTest")
    testClassesDirs = files()
    classpath = files()
}

tasks.withType(Test::class).configureEach {
    // Always rerun tests by default
    outputs.upToDateWhen { false }
}

tasks.register("testStress", Test::class) {
    group = "verification"
    description = "Run stress-tagged tests"
    dependsOn(tasks.named("testClasses"))
    val mainTest = sourceSets.getByName("test")
    testClassesDirs = mainTest.output.classesDirs
    classpath = mainTest.runtimeClasspath
    useJUnitPlatform {
        includeTags("stress")
    }
}

tasks.register("testFuzz", Test::class) {
    group = "verification"
    description = "Run fuzzing tests"
    val fuzzing = sourceSets.getByName("fuzzingTest")
    testClassesDirs = fuzzing.output.classesDirs
    classpath = fuzzing.runtimeClasspath
    val outFile = layout.buildDirectory.file("reports/jazzer/jazzer.log").get().asFile
    doFirst {
        outFile.parentFile.mkdirs()
    }
    testLogging.showStandardStreams = false
    addTestOutputListener(object : org.gradle.api.tasks.testing.TestOutputListener {
        override fun onOutput(
            descriptor: org.gradle.api.tasks.testing.TestDescriptor,
            event: org.gradle.api.tasks.testing.TestOutputEvent
        ) {
            outFile.appendText(event.message)
        }
    })
    // Enable Jazzer fuzzing mode for @FuzzTest
    environment("JAZZER_FUZZ", "1")
    environment("JAZZER_DICTIONARY", "$projectDir/src/test/fuzzing/resources/fuzz/authparser.dict")
    // Keep fuzzing time bounded during stress runs
    environment("JAZZER_MAX_TOTAL_TIME", "120")// seconds
    useJUnitPlatform {
        includeTags("stress")
    }
}

tasks.register("testLocal", Test::class) {
    group = "verification"
    description = "Run local-tagged tests (e.g., Testcontainers registry)"
    dependsOn(tasks.named("testClasses"))
    val mainTest = sourceSets.getByName("test")
    testClassesDirs = mainTest.output.classesDirs
    classpath = mainTest.runtimeClasspath
    useJUnitPlatform {
        includeTags("local")
    }
}

tasks.register("testPorto", Test::class) {
    group = "verification"
    description = "Run porto-tagged tests (Porto runtime/manual env)"
    useJUnitPlatform {
        includeTags("porto")
    }
}

tasks.register("testNoFilesystem", Test::class) {
    group = "verification"
    description = "Run tests excluding filesystem-tagged tests"
    useJUnitPlatform {
        excludeTags("filesystem")
    }
}

tasks.register("testAll") {
    group = "verification"
    description = "Run default tests and stress tests"
    dependsOn("test", "testStress")
}

tasks.register("integrationTest", Test::class) {
    description = "Runs integration tests."
    group = "verification"
    val integration = sourceSets.getByName("integrationTest")
    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath
    useJUnitPlatform()
}

tasks.register("performanceTest", Test::class) {
    description = "Runs performance tests."
    group = "verification"
    val performance = sourceSets.getByName("performanceTest")
    testClassesDirs = performance.output.classesDirs
    classpath = performance.runtimeClasspath
    useJUnitPlatform()
}

tasks.register("moduledTest", Test::class) {
    description = "Runs moduled tests."
    group = "verification"
    val moduled = sourceSets.getByName("moduledTest")
    testClassesDirs = moduled.output.classesDirs
    classpath = moduled.runtimeClasspath
    useJUnitPlatform()
}

fun registerModuleTest(name: String, pattern: String, descriptionText: String) {
    tasks.register(name, Test::class) {
        group = "verification"
        description = descriptionText
        dependsOn(tasks.named("testClasses"))
        val mainTest = sourceSets.getByName("test")
        testClassesDirs = mainTest.output.classesDirs
        classpath = mainTest.runtimeClasspath
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

registerModuleTest(
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

