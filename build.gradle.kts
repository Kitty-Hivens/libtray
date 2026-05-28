plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

// Dedicated configuration so the smoke harness can pull in an SLF4J
// binding (slf4j-simple) without polluting the published library
// artifact. The library API exposes slf4j-api only; consumers bring
// their own binding (logback, log4j2, etc).
val smokeRuntime: Configuration by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
}

dependencies {
    "smokeRuntime"(libs.slf4j.simple)
}

tasks.register<JavaExec>("runSmoke") {
    group = "verification"
    description = "Open a tray icon and print click events. Ctrl-C to exit."
    classpath = sourceSets.main.get().runtimeClasspath + smokeRuntime
    mainClass.set("dev.hivens.libtray.SmokeMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // macOS pins NSStatusItem (and every NSWindow constructor it
    // delegates to) to OS thread 0 — the actual process main thread.
    // Without `-XstartOnFirstThread`, the JVM main thread runs on a
    // worker thread that AppKit refuses with
    //   *** NSInternalInconsistencyException: NSWindow should only
    //       be instantiated on the main thread!
    // AWT EDT also doesn't fit — it's a separate thread spawned by
    // CocoaToolkit. The flag makes JVM main == OS thread 0 == AppKit
    // main thread, so libtray's calls land where Cocoa wants them.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
    // Show INFO-level logs so the smoke harness surfaces backend
    // diagnostics ("window class registered", "NIM_ADD succeeded",
    // GetLastError on failure paths). Default slf4j-simple level is INFO
    // already, but the system property makes the intent explicit and
    // future-proofs against the default flipping in a newer release.
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    systemProperty("org.slf4j.simpleLogger.showDateTime", "true")
    systemProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS")
    standardInput = System.`in`
    // Don't fail the build if a developer aborts the smoke with Ctrl-C —
    // SIGINT exits the JVM with code 130, JavaExec treats that as failure.
    isIgnoreExitValue = true
}

// JavaFX repro harness for issue #5 (macOS tray crash inside a JavaFX
// host). Isolated source set so JavaFX never reaches the published
// artifact, the main compile, or the test classpath. JavaFX artifacts are
// platform-classified, so pick the classifier matching whoever runs the
// task (only macOS actually reproduces #5; other OSes just sanity-check
// JavaFX/libtray coexistence).
val javafxVersion = "25.0.3"
val javafxClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val aarch64 = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    when {
        os.contains("mac") || os.contains("darwin") -> if (aarch64) "mac-aarch64" else "mac"
        os.contains("win") -> if (aarch64) "win-aarch64" else "win"
        else -> if (aarch64) "linux-aarch64" else "linux"
    }
}

val javafxSmoke: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    "javafxSmokeImplementation"(libs.slf4j.api)
    "javafxSmokeRuntimeOnly"(libs.slf4j.simple)
    listOf("javafx-base", "javafx-graphics").forEach { module ->
        "javafxSmokeImplementation"("org.openjfx:$module:$javafxVersion:$javafxClassifier")
    }
}

tasks.register<JavaExec>("runJavaFxSmoke") {
    group = "verification"
    description = "Issue #5 repro: create a libtray tray inside a live JavaFX host (macOS-focused)."
    classpath = javafxSmoke.runtimeClasspath
    mainClass.set("dev.hivens.libtray.JavaFxSmokeMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // JavaFX manages its own main-thread acquisition on macOS, so unlike
    // runSmoke we do NOT force -XstartOnFirstThread by default (matches a
    // normal JavaFX launch). If JavaFX refuses to start on a given host
    // complaining about the main thread, retry with `-PfxFirstThread`.
    if (project.hasProperty("fxFirstThread") &&
        System.getProperty("os.name").lowercase().contains("mac")
    ) {
        jvmArgs("-XstartOnFirstThread")
    }
    // Force JavaFX's software render pipeline. JavaFX 25 defaults to Metal
    // on macOS, which cannot initialise on a GPU-less host: a QEMU macOS
    // VM dies in MTLContext_nInitialize ("Failed to create shader
    // library") before any tray code runs. The #5 crash lives in Glass's
    // CVDisplayLink pulse timer, independent of the render backend, so
    // software rendering keeps the repro valid while letting JavaFX start
    // anywhere (VM, CI, headless).
    systemProperty("prism.order", "sw")
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    systemProperty("org.slf4j.simpleLogger.showDateTime", "true")
    systemProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS")
    standardInput = System.`in`
    isIgnoreExitValue = true
}

group = "dev.hivens"
// Version comes from the git tag at CI time via `-PappVersion=<tag>`;
// falls back to `git describe` for local development. Mirrors Aura's
// pattern so a shared developer mental model.
version = providers.gradleProperty("appVersion")
    .orElse(providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "0.0.0-SNAPSHOT" } })
    .getOrElse("0.0.0-SNAPSHOT")

java {
    // Source / target Java 22 — Project Panama (java.lang.foreign)
    // finalized as JEP 454. Compiles with any JDK >= 22 in the build
    // environment; CI uses Temurin 22, the maintainer's local install
    // is JBR 25 (compatible). No toolchain auto-provision plugin —
    // contributors install their own JDK rather than have Gradle
    // download one transparently.
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
        // Project Panama (java.lang.foreign) is finalized in JDK 22 — no
        // --enable-preview needed.
        freeCompilerArgs.addAll(
            "-jvm-default=enable",  // Generate Java 8+ default methods for interface APIs.
        )
    }
}

dependencies {
    // SLF4J only — consumers wire their own backend. The library logs at
    // DEBUG/INFO/WARN; nothing should fire at ERROR in normal operation
    // (failures degrade by returning null/false, not by throwing).
    api(libs.slf4j.api)

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
    // Native code under test will eventually want this; harmless on tests
    // that don't reach Panama.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "libtray",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Kitty-Hivens",
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("libtray")
                description.set("Cross-platform system tray for JVM 22+ via Project Panama.")
                url.set("https://github.com/Kitty-Hivens/libtray")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("kitty-hivens")
                        name.set("Kitty-Hivens")
                    }
                }
                scm {
                    url.set("https://github.com/Kitty-Hivens/libtray")
                    connection.set("scm:git:https://github.com/Kitty-Hivens/libtray.git")
                }
            }
        }
    }
}
