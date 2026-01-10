import org.gradle.api.logging.configuration.ConsoleOutput

// Enable rich console only for test-related tasks
val testTasks = setOf("test", "testAll", "testStress", "testLocal", "allReports")
if (gradle.startParameter.taskNames.any { name -> testTasks.any { task -> name.contains(task, ignoreCase = true) } }) {
    gradle.startParameter.consoleOutput = ConsoleOutput.Rich
}

rootProject.name = "Riid"