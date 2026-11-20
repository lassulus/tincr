plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.thalheim.tincr.ondemand"
    compileSdk = 35
    buildToolsVersion = "35.0.0" // must exist in the nix SDK (read-only)

    defaultConfig {
        applicationId = "io.thalheim.tincr.ondemand"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters += listOf("arm64-v8a", "x86_64")
    }

    // tincd ships as jniLibs/<abi>/libtincd.so. Exec from
    // nativeLibraryDir needs extracted files (API 29 noexec).
    packaging.jniLibs.useLegacyPackaging = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    // The main app's two-node test mesh (keys, hosts, configs).
    sourceSets["androidTest"].assets.srcDir("../../android/app/src/androidTest/assets")
}

// Plain framework Views: no runtime dependencies besides the Kotlin stdlib.
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
