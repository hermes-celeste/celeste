plugins {
    id("com.android.application")
    id("com.android.compose.screenshot")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
}

val testKeystorePath = providers.environmentVariable("CELESTE_TEST_KEYSTORE_PATH").orNull

android {
    namespace = "dev.hazydreams.hermesceleste"
    compileSdk = 37
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "dev.hazydreams.hermesceleste"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (testKeystorePath != null) {
            create("ciTest") {
                storeFile = file(testKeystorePath)
                storePassword = providers.environmentVariable("CELESTE_TEST_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("CELESTE_TEST_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("CELESTE_TEST_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("ciTest")?.let { signingConfig = it }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    val okhttpBom = platform("com.squareup.okhttp3:okhttp-bom:5.5.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(okhttpBom)
    testImplementation(okhttpBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.43.0")
    implementation("org.jetbrains:markdown:0.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    screenshotTestImplementation(composeBom)
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
}
