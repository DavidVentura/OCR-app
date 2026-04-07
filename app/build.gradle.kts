import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun findAndroidSdkDir(): String {
    val sdkFromEnv = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    if (sdkFromEnv != null) {
        return sdkFromEnv
    }

    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val properties = Properties()
        localPropertiesFile.inputStream().use(properties::load)
        properties.getProperty("sdk.dir")?.let { return it }
    }

    error("Android SDK not found. Set ANDROID_SDK_ROOT or add sdk.dir to local.properties.")
}

val ndkVersionName = "28.0.12674087"
val androidApiLevel = 24
val targetAbi = project.findProperty("targetAbi")?.toString() ?: "arm64-v8a"
val bindingsRootDir = "src/main/bindings"
val jniLibsOutputDir = "../jniLibs"
val ndkPath = "${findAndroidSdkDir()}/ndk/$ndkVersionName"
val bindingsOutputDir = layout.projectDirectory.dir("src/main/jniLibs/$targetAbi")

android {
    namespace = "dev.davidv.ocr"
    compileSdk = 34
    ndkVersion = ndkVersionName

    defaultConfig {
        applicationId = "dev.davidv.ocr"
        minSdk = androidApiLevel
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += targetAbi
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

tasks.register("buildBindings") {
    group = "build"
    description = "Build Rust OCR bindings library for $targetAbi"
    inputs.dir(layout.projectDirectory.dir(bindingsRootDir))
    inputs.dir(layout.projectDirectory.dir("../third_party/rust-paddle-ocr/src"))
    inputs.file(layout.projectDirectory.file("../third_party/rust-paddle-ocr/Cargo.toml"))
    inputs.file(layout.projectDirectory.file("../third_party/rust-paddle-ocr/build.rs"))
    inputs.property("targetAbi", targetAbi)
    inputs.property("androidApiLevel", androidApiLevel)
    inputs.property("ndkPath", ndkPath)
    outputs.dir(bindingsOutputDir)

    doLast {
        exec {
            workingDir = file(bindingsRootDir)
            environment("ANDROID_NDK_ROOT", ndkPath)
            environment("ANDROID_NDK_HOME", ndkPath)
            commandLine(
                "cargo",
                "ndk",
                "build",
                "--target",
                targetAbi,
                "--release",
                "--platform",
                androidApiLevel.toString(),
                "--link-libcxx-shared",
                "--output-dir",
                jniLibsOutputDir,
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("buildBindings")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
