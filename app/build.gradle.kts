plugins {
    alias(libs.plugins.android.application)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.presensor"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        viewBinding = false
        dataBinding = true
    }

    defaultConfig {
        applicationId = "com.example.presensor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            // Enable coverage for instrumentation (androidTest)
            enableAndroidTestCoverage = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/index.list"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion") // Use ksp here
    // Note: If using Kotlin, use 'kapt' or 'ksp'. Ensure the plugin is applied.
    annotationProcessor("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.cardview:cardview:1.0.0")
    // Kotlin Coroutines Core (Provides Flow, StateFlow, SharedFlow)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Android support for Coroutines (Provides Dispatchers.Main, lifecycleScope, etc.)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 1. Authentication
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // 2. Google Drive Client SDK
    implementation("com.google.api-client:google-api-client-android:2.6.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240521-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // 3. Google Sheets Client SDK
    implementation("com.google.apis:google-api-services-sheets:v4-rev20240416-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
}