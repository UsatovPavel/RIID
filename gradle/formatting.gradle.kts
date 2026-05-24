import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.closureOf
import org.gradle.kotlin.dsl.withGroovyBuilder

val spotless = extensions.getByName("spotless") as ExtensionAware
spotless.withGroovyBuilder {
    "java"(closureOf<Any> {
        withGroovyBuilder {
            "target"(
                "src/main/java/**/*.java",
                "src/test/**/*.java",
                "src/testFixtures/**/*.java",
            )
            ("eclipse"() as Any).withGroovyBuilder {
                "configFile"("$rootDir/config/formatting/eclipse-java-formatter.xml")
            }
            "trimTrailingWhitespace"()
            "endWithNewline"()
        }
    })
}

tasks.register("format") {
    group = "formatting"
    description = "Formats Java sources via Spotless Eclipse formatter."
    dependsOn("spotlessApply")
}

tasks.register("formatCheck") {
    group = "verification"
    description = "Checks Java formatting via Spotless."
    dependsOn("spotlessCheck")
}
