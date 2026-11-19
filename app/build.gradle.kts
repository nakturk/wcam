plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wcam.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.wcam.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        resourceConfigurations += listOf("en", "tr")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    constraints {
        implementation("androidx.activity:activity:1.9.3")
        implementation("androidx.annotation:annotation:1.8.2")
        implementation("androidx.collection:collection:1.4.4")
        implementation("androidx.concurrent:concurrent-futures:1.1.0")
        implementation("androidx.core:core:1.15.0")
        implementation("androidx.lifecycle:lifecycle-common:2.8.7")
        implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")
        implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
        implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
        implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
    }
    testImplementation("junit:junit:4.13.2")
}
