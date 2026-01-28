import org.gradle.api.tasks.Exec

//для запуска Docker у тасок вывод некрасивый по сравнению с Makefile
tasks.register("dockerBuild", Exec::class) {
    group = "docker"
    description = "Build demo image (riid-demo)"
    commandLine("docker", "build", "-t", "riid-demo", ".")
}

val dockerBuildTestImage = tasks.register("dockerBuildTestImage", Exec::class) {
    group = "docker"
    description = "Build test image (builder target)"
    commandLine(
        "docker", "build",
        "--target", "builder",
        "-t", "riid-test", "."
    )
}

val dockerRunTestsInContainer = tasks.register("dockerRunTestsInContainer", Exec::class) {
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

