import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
}

abstract class CheckSttApiSurfaceTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @TaskAction
    fun verify() {
                                val expectedPublicApiTypes = setOf(
                    "SpeechToText",
                    "SpeechToTextProvider",
                    "SttConfig",
                    "SessionResult",
                    "SttReturnCode",
                    "SttError",
                    "SttErrorCode",
                    "SttErrorCategory",
                    "SttErrorListener",
                    "SttTimingSnapshot",
                    "DrainMode",
                    "StartTrigger",
                    "StopTrigger"
                )

        val sttSources = sourceDir.get().asFile
        val kotlinFiles = sttSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        val publicTopLevelTypes = mutableSetOf<String>()
        val publicRegex = Regex("^(?:data\\s+class|enum\\s+class|sealed\\s+(?:class|interface)|fun\\s+interface|class|object|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")

        kotlinFiles.forEach { sourceFile ->
            sourceFile.readLines().forEach { rawLine ->
                if (rawLine.startsWith(" ") || rawLine.startsWith("\t")) {
                    return@forEach
                }

                val line = rawLine.trim()
                if (line.startsWith("internal ") || line.startsWith("private ")) {
                    return@forEach
                }

                val match = publicRegex.find(line)
                if (match != null) {
                    publicTopLevelTypes += match.groupValues[1]
                }
            }
        }

        val missing = expectedPublicApiTypes - publicTopLevelTypes
        val unexpected = publicTopLevelTypes - expectedPublicApiTypes
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw GradleException(
                "stt API surface mismatch. Missing: ${missing.sorted()} | Unexpected: ${unexpected.sorted()}"
            )
        }

        val whisperBridgeSource = sttSources.resolve("WhisperBridge.kt").readText()
        if (!whisperBridgeSource.contains("internal object WhisperBridge")) {
            throw GradleException("WhisperBridge must be internal.")
        }
        if (!whisperBridgeSource.contains("external fun loadModel(modelPath: String)")) {
            throw GradleException("WhisperBridge must expose loadModel(modelPath: String).")
        }
        if (!whisperBridgeSource.contains("external fun transcribe(samples: ShortArray): String")) {
            throw GradleException("WhisperBridge must expose transcribe(samples: ShortArray): String.")
        }
        if (whisperBridgeSource.contains("external fun init(")) {
            throw GradleException("Legacy handle-based init API must not be exposed.")
        }
    }
}

android {
    namespace = "dev.barrycade.voicecore.stt"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "28.0.12674087"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

tasks.register<CheckSttApiSurfaceTask>("checkSttApiSurface") {
    group = "verification"
    description = "Ensures the stt module only exposes the approved public API surface."
    sourceDir.set(layout.projectDirectory.dir("src/main/java/dev/barrycade/voicecore/stt"))
}

tasks.named("check").configure {
    dependsOn("checkSttApiSurface")
}
