plugins {
    java
    application
}

group = "io.aeronfire"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val sbeCodegen: Configuration by configurations.creating

dependencies {
    implementation(libs.aeron.client)
    implementation(libs.aeron.driver)
    implementation(libs.aeron.archive)
    implementation(libs.aeron.cluster)
    implementation(libs.aeron.samples)
    implementation(libs.hdrhistogram)

    sbeCodegen(libs.sbe.tool)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// JVM flags Aeron/Agrona need on JDK 24+ (Unsafe memory access is deprecated for removal).
val aeronJvmArgs = listOf(
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
)

val sbeSchema = layout.projectDirectory.file("src/main/resources/sbe/aeronfire-schema.xml")
val sbeOutput = layout.buildDirectory.dir("generated/sources/sbe")

val generateCodecs by tasks.registering(JavaExec::class) {
    description = "Generates SBE Java codecs from the message schema."
    group = "build"
    inputs.file(sbeSchema)
    outputs.dir(sbeOutput)
    classpath = sbeCodegen
    mainClass = "uk.co.real_logic.sbe.SbeTool"
    jvmArgs(aeronJvmArgs)
    systemProperty("sbe.target.language", "Java")
    systemProperty("sbe.validation.stop.on.error", "true")
    systemProperty("sbe.validation.warnings.fatal", "true")
    systemProperty("sbe.output.dir", sbeOutput.get().asFile.absolutePath)
    args(sbeSchema.asFile.absolutePath)
}

sourceSets {
    main {
        java.srcDir(sbeOutput)
    }
}

tasks.compileJava {
    dependsOn(generateCodecs)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-this-escape"))
    options.encoding = "UTF-8"
}

application {
    mainClass = "io.aeronfire.cluster.ClusterNode"
    applicationDefaultJvmArgs = aeronJvmArgs + listOf("-XX:+UseZGC", "-Dagrona.disable.bounds.checks=true")
}

// Second launcher in the distribution: bin/rfq-client alongside bin/aeronfire.
val clientStartScripts by tasks.registering(CreateStartScripts::class) {
    mainClass = "io.aeronfire.client.RfqClient"
    applicationName = "rfq-client"
    outputDir = layout.buildDirectory.dir("client-scripts").get().asFile
    classpath = tasks.startScripts.get().classpath
    defaultJvmOpts = aeronJvmArgs
}

distributions {
    main {
        contents {
            from(clientStartScripts) { into("bin") }
        }
    }
}

tasks.register<JavaExec>("runClient") {
    description = "Runs the RFQ client against a local 3-node cluster."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.client.RfqClient"
    jvmArgs(aeronJvmArgs)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(aeronJvmArgs)
}
