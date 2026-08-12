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
        versionCode = 2
        versionName = "2.0"

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
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.ar:core:1.54.0")

    testImplementation("junit:junit:4.13.2")
}
