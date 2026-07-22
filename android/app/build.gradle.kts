import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

android {
    namespace = "com.askphotos.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.askphotos.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("offlineDemo") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_MODEL_DOWNLOAD", "false")
            buildConfigField("String", "DISTRIBUTION", "\"offlineDemo\"")
        }
        create("consumer") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_MODEL_DOWNLOAD", "true")
            buildConfigField("String", "DISTRIBUTION", "\"consumer\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir("../../demo-assets")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.3")
    implementation("androidx.compose.foundation:foundation:1.10.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation(libs.google.ai.edge.litert)
    implementation(libs.microsoft.onnxruntime.android)
    kapt(libs.androidx.room.compiler)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.3")
}
