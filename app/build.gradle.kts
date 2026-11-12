plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nominal SI export; retain A control polling (post-write 10ms).

android {
    namespace = "com.egouvc.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.egouvc.phone"
        minSdk = 31
        targetSdk = 35
        versionCode = 17
        versionName = "0.3.6-single-page"
    }

    defaultConfig {
        buildConfigField("boolean", "IMU_POLL_START_BASED", "false")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":uvccamera"))
    testImplementation("junit:junit:4.13.2")
}
