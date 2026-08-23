plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.com.elied.vitalsignal.governance"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}
