plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "au.com.elied.vitalsignal.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "au.com.elied.vitalsignal"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "0.6.0-research"

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
    implementation(project(":core:analytics"))
    implementation(project(":core:transport"))
    implementation(project(":core:storage"))
    implementation(project(":core:audit"))
    implementation(project(":core:reasoning"))
    implementation(project(":core:governance"))
    implementation(project(":core:monitoring"))

    // Samsung Health Data SDK is distributed as an AAR. Keeping this file tree
    // empty leaves the open foundation buildable until the licensed AAR is added.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.health.connect)
    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
