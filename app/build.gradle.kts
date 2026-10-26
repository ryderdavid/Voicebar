import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ryderdavid.voicebar"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ryderdavid.voicebar"
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 37
        versionCode = 144
        versionName = "1.8.5"
        // the wake-word engine's native code: 64-bit ARM phones only (the Fold and every recent phone)
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key lives outside the repo (on Ryder's Mac at ~/Code/BubbleKeyboard-signing/).
    // Point local.properties at it with bk.signingDir=<folder holding signing.properties>.
    val signing = Properties().apply {
        val local = rootProject.file("local.properties")
        val dir = if (local.exists()) Properties().apply { local.inputStream().use { load(it) } }.getProperty("bk.signingDir") else null
        val f = dir?.let { File(it, "signing.properties") }
        if (f != null && f.exists()) { f.inputStream().use { load(it) }; setProperty("bk.dir", dir) }
    }
    signingConfigs {
        if (signing.getProperty("bk.storeFile") != null) create("bubble") {
            storeFile = File(signing.getProperty("bk.dir"), signing.getProperty("bk.storeFile"))
            storePassword = signing.getProperty("bk.storePassword")
            keyAlias = signing.getProperty("bk.keyAlias")
            keyPassword = signing.getProperty("bk.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // the permanent release key when it's available, otherwise the (per-machine) debug key
            signingConfig = signingConfigs.findByName("bubble") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    // compress the wake-word engine's native libraries in the APK (about 30 MB stored, 12 MB packed)
    packaging { jniLibs.useLegacyPackaging = true }
    // the swipe models are copied out of the APK as they are, so they're stored uncompressed
    androidResources { noCompress += "pte" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // an emulator Gradle sets up itself, for the checks that need real Android (the swipe models
        // in ExecuTorch): ./gradlew pixel6api34DebugAndroidTest
        managedDevices {
            localDevices {
                create("pixel6api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
        unitTests.isIncludeAndroidResources = true
        // a fresh JVM per test class keeps Robolectric's native-graphics sandboxes from piling up
        unitTests.all {
            it.forkEvery = 1
            it.maxHeapSize = "1024m"
            // the preview/animation renderers are tools, not checks: run them with -Prender
            if (!project.hasProperty("render")) it.exclude("**/Render*Test*")
            // RenderVideoTest films only these scenes (-Pscene=keyboard,swipe)
            if (project.hasProperty("scene")) it.systemProperty("scene", project.property("scene").toString())
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    // WebSocket streaming to the speech service and HTTPS to the cleanup model
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // on-device "voice bar" spotting: sherpa-onnx 1.13.8 (Apache 2.0), the official release AAR
    // trimmed to arm64-v8a (sha256 of the original: 633c2432…bd96)
    implementation(files("libs/sherpa-onnx-1.13.8-arm64.aar"))
    // swipe typing: runs FUTO Swipe's models (assets/swipe/); BSD license, with fbjni and SoLoader (Apache 2.0)
    implementation("org.pytorch:executorch-android:1.5.1")
    // the settings screen: Jetpack Compose with Material 3 (dynamic color)
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

composeCompiler {
    // the mapping task doesn't survive Gradle's configuration cache here, and we don't ship mappings
    includeComposeMappingFile.set(false)
}
