plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.com.elied.vitalsignal.audit"
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
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    testImplementation(libs.junit)
}
