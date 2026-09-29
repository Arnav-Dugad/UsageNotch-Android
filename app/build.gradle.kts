plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "io.github.arnavdugad.usagenotch"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.arnavdugad.usagenotch"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (System.getenv("USAGENOTCH_KEYSTORE") != null) create("release") {
            storeFile = file(System.getenv("USAGENOTCH_KEYSTORE"))
            storePassword = System.getenv("USAGENOTCH_STORE_PASSWORD")
            keyAlias = "usagenotch"
            keyPassword = System.getenv("USAGENOTCH_STORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Identical optimized build signed with the debug key, so R8 output can be launch-tested on an emulator.
        create("smoke") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    // In-app QR scanner: CameraX preview with ZXing decoding. Pure Java, no Play services module and no native libraries.
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
    // Liquid glass: backdrop refraction (Android 13+), blur and vibrancy (Android 12+), specular highlights and
    // continuous-corner shapes. 1.0.6 matches Compose 1.10; 2.x needs Compose 1.11+.
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
