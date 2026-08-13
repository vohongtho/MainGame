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
        versionCode = 3
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Geospatial API may use either keyless authorization or an API key. For API-key
        // authorization CI/prod should provide ARCORE_API_KEY as an environment variable/secret.
        // Keeping a placeholder lets keyless-authorized builds compile without committing secrets.
        val arcoreApiKey = System.getenv("ARCORE_API_KEY")?.takeIf { it.isNotBlank() } ?: "UNCONFIGURED"
        manifestPlaceholders["ARCORE_API_KEY"] = arcoreApiKey
        buildConfigField("boolean", "ARCORE_API_KEY_PRESENT", (arcoreApiKey != "UNCONFIGURED").toString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
