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
val fixCodegen: Configuration by configurations.creating

dependencies {
    implementation(libs.aeron.client)
    implementation(libs.aeron.driver)
    implementation(libs.aeron.archive)
    implementation(libs.aeron.cluster)
    implementation(libs.aeron.samples)
    implementation(libs.hdrhistogram)
    implementation(libs.artio.core)
    implementation(libs.artio.codecs)
    implementation(libs.artio.session.codecs)

    sbeCodegen(libs.sbe.tool)
    fixCodegen(libs.artio.codecs)

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

val fixDictionary = layout.projectDirectory.file("src/main/resources/fix/aeronfire-fix44.xml")
val fixOutput = layout.buildDirectory.dir("generated/sources/fix")

val generateFixCodecs by tasks.registering(JavaExec::class) {
    description = "Generates Artio FIX codecs from the RFQ dictionary."
    group = "build"
    inputs.file(fixDictionary)
    outputs.dir(fixOutput)
    classpath = fixCodegen
    mainClass = "uk.co.real_logic.artio.dictionary.CodecGenerationTool"
    jvmArgs(aeronJvmArgs)
    systemProperty("fix.codecs.parent_package", "io.aeronfire.fix")
    args(fixOutput.get().asFile.absolutePath, fixDictionary.asFile.absolutePath)
}

sourceSets {
    main {
        java.srcDir(sbeOutput)
        java.srcDir(fixOutput)
    }
}

tasks.compileJava {
    dependsOn(generateCodecs, generateFixCodecs)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-this-escape"))
    options.encoding = "UTF-8"
}

application {
    mainClass = "io.aeronfire.cluster.ClusterNode"
    applicationDefaultJvmArgs = aeronJvmArgs + listOf("-XX:+UseZGC", "-Dagrona.disable.bounds.checks=true")
}

// Extra launchers in the distribution alongside bin/aeronfire (the cluster node).
val launchers = mapOf(
    "rfq-client" to "io.aeronfire.client.RfqClient",
    "fix-gateway" to "io.aeronfire.gateway.FixGateway",
    "venue-sim" to "io.aeronfire.venue.VenueSimulator",
)

val extraStartScripts = launchers.map { (name, main) ->
    tasks.register<CreateStartScripts>("${name}StartScripts") {
        mainClass = main
        applicationName = name
        outputDir = layout.buildDirectory.dir("scripts-$name").get().asFile
        classpath = tasks.startScripts.get().classpath
        defaultJvmOpts = aeronJvmArgs + listOf("-Djava.net.preferIPv4Stack=true")
    }
}

distributions {
    main {
        contents {
            extraStartScripts.forEach { from(it) { into("bin") } }
        }
    }
}

tasks.register<JavaExec>("runGateway") {
    description = "Runs the FIX gateway against a local 3-node cluster."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.gateway.FixGateway"
    jvmArgs(aeronJvmArgs)
}

tasks.register<JavaExec>("runVenue") {
    description = "Runs the Tradeweb-style venue simulator against a local gateway."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.venue.VenueSimulator"
    jvmArgs(aeronJvmArgs)
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
