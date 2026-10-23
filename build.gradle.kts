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
val jolokiaAgent: Configuration by configurations.creating { isTransitive = false }

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
    jolokiaAgent(variantOf(libs.jolokia.agent) { classifier("javaagent") })

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
    "monitor" to "io.aeronfire.monitor.Monitor",
    "control" to "io.aeronfire.control.ControlPlane",
    "refdata" to "io.aeronfire.refdata.RefDataService",
    "posttrade" to "io.aeronfire.posttrade.PostTradeService",
    "risk-as-of" to "io.aeronfire.tools.RiskAsOf",
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
            // Jolokia JVM agent, attached to the control plane with -javaagent (see docker-compose.yml).
            from(jolokiaAgent) {
                into("agent")
                rename { "jolokia-agent.jar" }
            }
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

tasks.register<JavaExec>("runMonitor") {
    description = "Runs the blotter (UI=web|tui|both, web on http://localhost:8080)."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.monitor.Monitor"
    jvmArgs(aeronJvmArgs)
    standardInput = System.`in`
}

tasks.register<JavaExec>("runControl") {
    description = "Runs the control plane: MBeans + Jolokia (:8778) + admin page (http://localhost:8090)."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.control.ControlPlane"
    jvmArgs(aeronJvmArgs)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${jolokiaAgent.singleFile.absolutePath}=port=8778,host=localhost")
    })
}

tasks.register<JavaExec>("runRefData") {
    description = "Runs the reference data service (REFDATA_MODE=snapshot|live, http://localhost:8095)."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.refdata.RefDataService"
    jvmArgs(aeronJvmArgs)
}

tasks.register<JavaExec>("runPostTrade") {
    description = "Runs post-trade: booking, clearing, MiFIR reporting, hospital (http://localhost:8096)."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.posttrade.PostTradeService"
    jvmArgs(aeronJvmArgs)
}

tasks.register<JavaExec>("runRiskAsOf") {
    description = "Rebuilds positions, mark, CS01 and DV01 as of a past journal point from an archived snapshot."
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.aeronfire.tools.RiskAsOf"
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
    useJUnitPlatform { excludeTags("failover") }
    jvmArgs(aeronJvmArgs)
}

// Slow: three real members in separate JVMs, leader killed mid-flow. Run with ./gradlew failoverTest.
val failoverTest by tasks.registering(Test::class) {
    description = "Kills the cluster leader mid-flow and checks no trade is lost or duplicated."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("failover") }
    jvmArgs(aeronJvmArgs)
    testLogging { showStandardStreams = true }
}
