import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.the

val sourceSets = the<SourceSetContainer>()

sourceSets.create("integrationTest") {
    java.srcDir("src/test/integration/java")
    resources.srcDir("src/test/integration/resources")
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}

sourceSets.create("performanceTest") {
    java.srcDir("src/test/performance/java")
    resources.srcDir("src/test/performance/resources")
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}

sourceSets.create("moduledTest") {
    java.srcDir("src/test/moduled/java")
    resources.srcDir("src/test/moduled/resources")
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}

sourceSets.named("test") {
    java.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.getByName("testImplementation"))
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.getByName("testRuntimeOnly"))
}

configurations.named("performanceTestImplementation") {
    extendsFrom(configurations.getByName("testImplementation"))
}
configurations.named("performanceTestRuntimeOnly") {
    extendsFrom(configurations.getByName("testRuntimeOnly"))
}

configurations.named("moduledTestImplementation") {
    extendsFrom(configurations.getByName("testImplementation"))
}
configurations.named("moduledTestRuntimeOnly") {
    extendsFrom(configurations.getByName("testRuntimeOnly"))
}

dependencies {
    add("integrationTestImplementation", testFixtures(project(":")))
    add("integrationTestRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    add("performanceTestImplementation", testFixtures(project(":")))
    add("performanceTestRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    add("moduledTestImplementation", testFixtures(project(":")))
    add("moduledTestRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

