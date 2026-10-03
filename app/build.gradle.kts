plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kratour.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kratour.game"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Pas de R8 : le jeu est léger, et certains environnements de build sur téléphone
            // (ex. CodeAssist) embarquent un R8 incompatible avec la version d'Android de l'appareil.
            isMinifyEnabled = false
            // Signé avec la clé de debug pour pouvoir installer directement l'APK de release.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":core"))
}
