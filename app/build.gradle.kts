import java.util.Properties

// VoiceTunes: plays your own music from a Navidrome (Subsonic) server, by voice.
plugins {
    id("com.android.application")
}

// The key release builds are signed with: keystore.properties beside settings.gradle.kts
// (neither it nor the key is in the repository). Without it a release build is not signed.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.ferrellsl.voicetunes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ferrellsl.voicetunes"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3"
        // phones and tablets (Vosk's speech library is ~10 MB per architecture)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // hands-free wake words, recognized offline (the model is downloaded when it's first turned on)
    implementation("com.alphacephei:vosk-android:0.3.75")
}
