plugins {
    id("com.android.library")
}

android {
    namespace = "dev.barrycade.voicecore.vosk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
}
