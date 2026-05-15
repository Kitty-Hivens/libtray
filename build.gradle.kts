plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

group = "io.github.kitty-hivens"
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
