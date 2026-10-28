plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.djstef.gps"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.djstef.gps"
        minSdk = 26
        targetSdk = 34
        // Le numéro de build GitHub garantit que chaque APK s'installe par-dessus le précédent.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.61"
    }

    // La clé de signature est générée et conservée par le workflow GitHub (jamais dans le dépôt).
    val ksPath: String? = System.getenv("KS_PATH")
    val ksPass: String? = System.getenv("KS_PASS")
    if (ksPath != null && ksPass != null) {
        signingConfigs {
            create("djstef") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = "djstef"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null && ksPass != null) {
                signingConfig = signingConfigs.getByName("djstef")
            }
        }
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
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    implementation("androidx.car.app:app:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
