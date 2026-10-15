import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version : la CI pose DICTEE_VERSION_CODE (numéro de run, croissant) et DICTEE_VERSION_NAME (« 1.0.<run> ») ;
// en local, 1 et « 1.0.dev ». Signature : la clé de release vient de l'environnement (secrets GitHub, ou signing.env
// hors dépôt en local) ; sans elle, la clé de débogage (une APK de débogage ne met jamais à jour l'APK publiée).
val vCode = (System.getenv("DICTEE_VERSION_CODE") ?: "1").toInt()
val vName = System.getenv("DICTEE_VERSION_NAME") ?: "1.0.dev"
val ksPath = System.getenv("DICTEE_KEYSTORE")
val hasRelease = !ksPath.isNullOrBlank() && File(ksPath).isFile

android {
    namespace = "fr.hadrienrose.dictee"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.hadrienrose.dictee"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName
        buildConfigField("String", "SITE", "\"https://voice.hadrien-rose.com\"")
        buildConfigField("String", "UPDATE_JSON", "\"https://github.com/hadrien-rose/dictee-android/releases/latest/download/version.json\"")
    }

    signingConfigs {
        if (hasRelease) {
            create("release") {
                storeFile = File(ksPath!!)
                storePassword = System.getenv("DICTEE_KEYSTORE_PASS")
                keyAlias = System.getenv("DICTEE_KEY_ALIAS") ?: "dictee"
                keyPassword = System.getenv("DICTEE_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (hasRelease) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // le vrai org.json pour les tests JVM (le jar Android n'en a que les signatures)
}
