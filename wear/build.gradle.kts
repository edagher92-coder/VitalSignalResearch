plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "au.com.elied.vitalsignal.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "au.com.elied.vitalsignal"
        minSdk = 30
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0-research"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:transport"))
    implementation(project(":core:storage"))
    implementation(project(":core:governance"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)

    // Official public watch-side APIs. Samsung's licensed AAR is intentionally
    // absent; wear/libs/README.md documents how the private adapter is added.
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
