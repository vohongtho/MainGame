plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.treedirectiondemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.treedirectiondemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")

    val cameraX = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.android.gms:play-services-location:21.4.0")

    testImplementation("junit:junit:4.13.2")
}
