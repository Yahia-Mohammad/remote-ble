plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    // Agent runs on the JVM (Kable's btleplug stack: macOS, Linux/rpi).
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("dev.warsha.ble.remoteble.agent.MainKt")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
            // Kable core: the unified KMP BLE stack.
            implementation(libs.kable.core)
        }
        // The WebSocket server + the native (btleplug) BLE engine are JVM-side.
        jvmMain.dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            // Koin: composition-root wiring consumed by Main.kt (see agent/di/AgentModule.kt).
            implementation(libs.koin.core)
            // JSON for the agent's live status dashboard (/api/state). See AgentMonitor.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Verifies the agent Koin graph resolves (AgentKoinTest); no server/radio.
            implementation(libs.koin.test)
        }
    }
}

// Prints the agent's JVM runtime classpath (compiled classes + dependencies) so the
// macOS launcher (agent/run-agent.sh) can pass it to the in-process JVM. See
// agent/macos-launcher/launcher.c for why the agent must run from a signed .app.
tasks.register("printJvmRuntimeClasspath") {
    notCompatibleWithConfigurationCache("Resolves and prints the runtime classpath on demand")
    dependsOn("jvmMainClasses")
    val classesDir = layout.buildDirectory.dir("classes/kotlin/jvm/main").get().asFile
    val runtime: FileCollection = configurations.getByName("jvmRuntimeClasspath")
    doLast {
        val sep = System.getProperty("path.separator")
        println(classesDir.path + sep + runtime.files.joinToString(sep) { it.path })
    }
}
