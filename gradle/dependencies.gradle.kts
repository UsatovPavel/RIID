dependencies {
    add("testFixturesImplementation", "org.slf4j:slf4j-api:2.0.13")

    // HTTP/Auth parsing (Apache HttpClient 5 + core)
    add("implementation", "org.apache.httpcomponents.client5:httpclient5:5.3.1")
    add("implementation", "org.apache.httpcomponents.core5:httpcore5:5.2.4")

    add("implementation", "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    add("testImplementation", platform("org.junit:junit-bom:5.10.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "ch.qos.logback:logback-classic:1.5.32")
    add("testImplementation", "net.logstash.logback:logstash-logback-encoder:9.0")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    add("testImplementation", "com.tngtech.archunit:archunit-junit5:1.3.0")
    add("testImplementation", platform("org.testcontainers:testcontainers-bom:1.21.4"))//idea say<=1.21 it vulnerable
    add("testImplementation", "org.testcontainers:junit-jupiter")//but this lib only in 1.21 version// TODO: at May check 2.0.3
    add("testImplementation", "org.testcontainers:testcontainers")//this lib has 2.0.3 version
    add("runtimeOnly", "ch.qos.logback:logback-classic:1.5.32")
    add("runtimeOnly", "net.logstash.logback:logstash-logback-encoder:9.0")
    add("testRuntimeOnly", "ch.qos.logback:logback-classic:1.5.12")
    add("compileOnly", "com.github.spotbugs:spotbugs-annotations:4.9.8")
    add("testCompileOnly", "com.github.spotbugs:spotbugs-annotations:4.9.8")
    add("implementation", "org.slf4j:slf4j-api:2.0.13")
    add("implementation", "com.fasterxml.jackson.core:jackson-databind:2.17.2")
    add("implementation", "com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    add("implementation", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    add("implementation", "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    add("implementation", "commons-codec:commons-codec:1.16.1")
    add("implementation", "commons-cli:commons-cli:1.11.0")
    add("implementation", "commons-io:commons-io:2.21.0")
    add("implementation", "com.github.ben-manes.caffeine:caffeine:3.1.8")
    add("implementation", "org.eclipse.jetty:jetty-client:12.1.5")
    add("implementation", "org.eclipse.jetty:jetty-server:12.1.5")
    add("implementation", "org.eclipse.jetty:jetty-util:12.1.5")
    add("implementation", "org.eclipse.jetty:jetty-unixdomain-server:12.1.5")
    add("implementation", "ru.hse:java-dragonfly-image-puller:1.2.0")

}

 

