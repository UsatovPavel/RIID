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
    useJUnitPlatform {
        includeTags("stress")
    }
}

tasks.register("testLocal", Test::class) {
    group = "verification"
    description = "Run local-tagged tests (e.g., Testcontainers registry)"
    useJUnitPlatform {
        includeTags("local")
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

