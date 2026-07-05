import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    // Compose Multiplatform for the Android/iOS status UI (mobileMain only — see below;
    // the jvm() CLI target never touches compose.*).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    // Agent runs on the JVM (Kable's btleplug stack: macOS, Linux/rpi), Android (Kable's
    // native Android BLE backend), and iOS (Kable's CoreBluetooth backend) — same commonMain
    // radio/protocol logic everywhere; only the composition root (CLI vs. foreground Service
    // vs. app process) differs per target.
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("dev.warsha.remoteble.agent.MainKt")
        }
    }
    // AGP 9 KMP library DSL (replaces androidTarget {} + the top-level android {} block) —
    // same as :client-sdk/:client-ui.
    android {
        namespace = "dev.warsha.remoteble.agent"
        compileSdk = libs.versions.android.compile.get().toInt()
        minSdk = libs.versions.android.min.get().toInt()
    }
    // No iosX64() (Intel Simulator): this module depends on compose.* and Compose Multiplatform
    // 1.11.1 doesn't publish for it — same call :client-ui makes. Apple Silicon Macs cover
    // iosArm64 (device) + iosSimulatorArm64 (simulator) either way.
    val appleTargets = listOf(iosArm64(), iosSimulatorArm64())

    // Opt-in Obj-C/Swift framework export for the ios-agent launcher shell. Off by default so a
    // plain CLT-only `./gradlew build` stays green (framework *linking* needs a full Xcode
    // toolchain). On a Mac with Xcode:
    //   ./gradlew :agent:assembleRemoteBleAgentReleaseXCFramework -PiosFramework
    if (providers.gradleProperty("iosFramework").isPresent) {
        val xcframework = XCFramework("RemoteBleAgent")
        appleTargets.forEach { target ->
            target.binaries.framework {
                baseName = "RemoteBleAgent"
                xcframework.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
            // Kable core: the unified KMP BLE stack. On the JVM its backend is btleplug; on
            // Android/iOS it's the platform's own native BLE stack (no btleplug involved there).
            implementation(libs.kable.core)
            // The WebSocket server + status dashboard now live here too (promoted from jvmMain —
            // Ktor server/websockets and Koin are all genuinely multiplatform).
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
            // Multiplatform lock/atomic primitives replacing the JVM-only
            // ConcurrentHashMap/AtomicLong/synchronized the promoted code used.
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Verifies the agent Koin graph resolves (AgentKoinTest); no server/radio.
            implementation(libs.koin.test)
        }
        jvmMain.dependencies {
            // The Compose compiler plugin (applied module-wide, below) requires the Compose
            // runtime on every compilation's classpath it touches, even one — like this CLI
            // target — with no `@Composable` code of its own. Not exposed as `api`; jvmMain
            // never actually uses it.
            implementation(compose.runtime)
        }

        // Compose UI + mobile entry-point glue (AgentApp, AgentRunner, AgentService/
        // IosAgentEntry). A custom intermediate source set rather than commonMain so the jvm()
        // CLI target's dependency graph never pulls in Compose Multiplatform.
        val mobileMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.ui)
            }
        }
        androidMain.get().dependsOn(mobileMain)
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // NotificationCompat for AgentService's foreground-service notification (handles
            // the pre/post-API-26 channel split so the service doesn't need to branch on it).
            implementation("androidx.core:core-ktx:1.19.0")
            // Persists the auth token across restarts (TokenStore.android.kt).
            implementation(libs.androidx.datastore.preferences)
        }
        // Touching androidMain/iosMain's dependsOn above opts this module out of the default
        // hierarchy template (Kotlin's warning: "explicit dependsOn edges... template not
        // applied"), so the iosArm64Main/iosSimulatorArm64Main -> iosMain edge it would
        // otherwise supply has to be wired by hand too.
        val iosMainSourceSet = iosMain.get().apply { dependsOn(mobileMain) }
        iosArm64Main.get().dependsOn(iosMainSourceSet)
        iosSimulatorArm64Main.get().dependsOn(iosMainSourceSet)
    }
}

// Building/running iOS binaries needs a full Xcode toolchain; the klibs still compile under
// CLT-only environments. Mirrors :client-sdk/:client-ui.
tasks.matching { it.name.contains("Test") && it.name.contains("ios", ignoreCase = true) }
    .configureEach { enabled = false }

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

// Self-contained executable JAR of the JVM agent: `java -jar remoteble-agent-<ver>-all.jar [port]`.
// For Linux/rpi (and Windows) — btleplug talks to BlueZ there with no TCC dance. macOS still needs
// the signed .app (CoreBluetooth/TCC — see run-agent.sh). Attached to GitHub Releases by
// .github/workflows/agent-artifacts.yml.
tasks.register<Jar>("jvmFatJar") {
    notCompatibleWithConfigurationCache("Merges the resolved runtime classpath via zipTree")
    group = "distribution"
    description = "Assembles a self-contained executable JAR of the JVM agent (java -jar …)."
    archiveBaseName.set("remoteble-agent")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "dev.warsha.remoteble.agent.MainKt") }

    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    from(jvmMain.output.allOutputs)
    from(configurations.getByName("jvmRuntimeClasspath").elements.map { entries ->
        entries.map { if (it.asFile.isDirectory) it.asFile else zipTree(it.asFile) }
    })
    // Merged dependency jars carry conflicting signatures / module descriptors.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
