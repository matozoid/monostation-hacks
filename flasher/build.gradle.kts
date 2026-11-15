// Circuit Mono Station firmware flasher (#63): Kotlin Multiplatform, MIDI through ktmidi.
// Common protocol and flash flow; a JVM command-line flasher and a Kotlin/JS page (own Web MIDI backend).
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

    js {
        outputModuleName.set("flasher")
        browser {
            commonWebpackConfig { outputFileName = "flasher.js" }
            testTask { enabled = false }       // common tests run under Node (jsNodeTest)
        }
        nodejs()
        binaries.executable()
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
