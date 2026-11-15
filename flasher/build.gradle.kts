// Circuit Mono Station firmware flasher (#63): Kotlin Multiplatform, MIDI through ktmidi.
// Spike: common protocol code plus a JVM command-line flasher; the Kotlin/JS page comes next.
plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("flasher.MainKt")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("dev.atsushieno:ktmidi:0.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation("dev.atsushieno:ktmidi-jvm-desktop:0.12.0")
        }
    }
}

// ktmidi's ALSA and RtMidi backends load native libraries through JavaCPP.
tasks.withType<JavaExec>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
