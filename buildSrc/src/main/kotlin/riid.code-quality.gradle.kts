import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.testing.jacoco.tasks.JacocoReport

val skipQuality = project.hasProperty("skipQuality")

extensions.configure(CheckstyleExtension::class) {
    configFile = file("config/checkstyle/checkstyle.xml")
}

tasks.withType(Checkstyle::class).configureEach {
    enabled = !skipQuality
    if (name != "checkstyleMain") {
        configFile = file("config/checkstyle/checkstyle-test.xml")
    }
}

tasks.withType(Pmd::class).configureEach {
    enabled = !skipQuality
    if (name != "pmdMain") {
        ruleSetFiles = files("config/pmd/pmd-test.xml")
        ruleSets = emptyList()
    }
}

tasks.withType(SpotBugsTask::class).configureEach {
    enabled = !skipQuality
    reports.create("html") {
        required.set(true)   // всегда генерить html-отчёт
    }
}

tasks.named("jacocoTestReport", JacocoReport::class) {
    enabled = !skipQuality
    dependsOn(tasks.named("test")) // jacoco runs only when explicitly invoked, but needs tests
}

tasks.register("allReports") {
    group = "verification"
    description = "Concatenate quality reports into build/reports/all-reports.html (best effort, no dependsOn check)"
    doLast {
        val qualityReportTaskNames = listOf(
            "checkstyleMain",
            "checkstyleModuledTest",
            "checkstyleIntegrationTest",
            "checkstylePerformanceTest",
            "pmdMain",
            "pmdModuledTest",
            "pmdIntegrationTest",
            "pmdPerformanceTest",
            "spotbugsMain",
            "spotbugsModuledTest",
            "spotbugsIntegrationTest",
            "spotbugsPerformanceTest"
        )
        val reports = listOf(
            "checkstyle/main.html",
            "checkstyle/moduledTest.html",
            "checkstyle/integrationTest.html",
            "checkstyle/performanceTest.html",
            "pmd/main.html",
            "pmd/moduledTest.html",
            "pmd/integrationTest.html",
            "pmd/performanceTest.html",
            "spotbugs/main.html",
            "spotbugs/moduledTest.html",
            "spotbugs/integrationTest.html",
            "spotbugs/performanceTest.html"
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
            append("<p>Expected quality tasks: ${qualityReportTaskNames.joinToString(", ")}</p>")
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
    val qualityTaskNames = listOf(
        "checkstyleMain",
        "checkstyleModuledTest",
        "checkstyleIntegrationTest",
        "checkstylePerformanceTest",
        "pmdMain",
        "pmdModuledTest",
        "pmdIntegrationTest",
        "pmdPerformanceTest",
        "spotbugsMain",
        "spotbugsModuledTest",
        "spotbugsIntegrationTest",
        "spotbugsPerformanceTest"
    ).filter(tasks.names::contains)

    // Explicit quality-only dependencies; tests are not part of check
    setDependsOn(qualityTaskNames.map(tasks::named))
    finalizedBy("allReports")
}

// Ensure allReports runs after individual quality tasks too (even on failure)
listOf(
    "checkstyleMain",
    "checkstyleModuledTest",
    "checkstyleIntegrationTest",
    "checkstylePerformanceTest",
    "pmdMain",
    "pmdModuledTest",
    "pmdIntegrationTest",
    "pmdPerformanceTest",
    "spotbugsMain",
    "spotbugsModuledTest",
    "spotbugsIntegrationTest",
    "spotbugsPerformanceTest"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        finalizedBy("allReports")
    }
}

