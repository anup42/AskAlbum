import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync
import java.security.MessageDigest

val embeddedSiglipArchive = rootProject.layout.projectDirectory.file("../build/siglip2-base-p16-224-q8-core05.agretrieval")
val generatedEmbeddedSiglipAssets = layout.buildDirectory.dir("generated/embeddedSiglip2Assets")
val prepareEmbeddedSiglip2Assets by tasks.registering(Sync::class) {
    from(embeddedSiglipArchive)
    into(generatedEmbeddedSiglipAssets.map { it.dir("models/retrieval") })
    doFirst {
        require(embeddedSiglipArchive.asFile.isFile) {
            "Missing pinned embedded SigLIP2 archive: ${embeddedSiglipArchive.asFile}"
        }
    }
}

val embeddedSfaceModel = rootProject.layout.projectDirectory.file("../build/models/face/face_recognition_sface_2021dec.onnx")
val generatedEmbeddedSfaceAssets = layout.buildDirectory.dir("generated/embeddedSfaceAssets")
val prepareEmbeddedSfaceAssets by tasks.registering(Sync::class) {
    from(embeddedSfaceModel)
    into(generatedEmbeddedSfaceAssets.map { it.dir("models/face") })
    doFirst {
        val file = embeddedSfaceModel.asFile
        require(file.isFile && file.length() == 38_696_353L) {
            "Missing or incomplete pinned SFace model: $file"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        require(sha256 == "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79") {
            "Pinned SFace SHA-256 mismatch: $sha256"
        }
    }
}

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
    namespace = "io.github.anup42.askalbum"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.anup42.askalbum"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            applicationIdSuffix = ".benchmark"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("offlineDemo") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_MODEL_DOWNLOAD", "false")
            buildConfigField("boolean", "MODEL_INDEPENDENT", "false")
            buildConfigField("String", "DISTRIBUTION", "\"offlineDemo\"")
        }
        create("consumer") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_MODEL_DOWNLOAD", "true")
            buildConfigField("boolean", "MODEL_INDEPENDENT", "false")
            buildConfigField("String", "DISTRIBUTION", "\"consumer\"")
        }
        create("ci") {
            dimension = "distribution"
            applicationIdSuffix = ".ci"
            versionNameSuffix = "-ci"
            buildConfigField("boolean", "ALLOW_MODEL_DOWNLOAD", "false")
            buildConfigField("boolean", "MODEL_INDEPENDENT", "true")
            buildConfigField("String", "DISTRIBUTION", "\"ci\"")
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
    sourceSets["offlineDemo"].assets.srcDir(generatedEmbeddedSiglipAssets)
    sourceSets["offlineDemo"].assets.srcDir(generatedEmbeddedSfaceAssets)
    sourceSets["consumer"].assets.srcDir(generatedEmbeddedSiglipAssets)
    sourceSets["consumer"].assets.srcDir(generatedEmbeddedSfaceAssets)
    androidResources.noCompress += listOf("agretrieval", "onnx")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.configureEach {
    val modelBearingVariant = !name.contains("Ci", ignoreCase = true)
    if (modelBearingVariant && ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("Lint", ignoreCase = true))) {
        dependsOn(prepareEmbeddedSiglip2Assets, prepareEmbeddedSfaceAssets)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:ocr-paddle"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation(libs.google.ai.edge.litert)
    implementation(libs.microsoft.onnxruntime.android)
    kapt(libs.androidx.room.compiler)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.3")
}
