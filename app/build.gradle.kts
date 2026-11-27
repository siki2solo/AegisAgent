import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "com.siki2solo.aegisagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.siki2solo.aegisagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val envPath = System.getenv("RELEASE_KEYSTORE_PATH")
            val propPath = keystoreProps.getProperty("storeFile")
            val resolvedPath = envPath ?: propPath ?: "release-keystore.p12"
            val candidateFile = if (resolvedPath.startsWith("/")) {
                file(resolvedPath)
            } else {
                project.file(resolvedPath)
            }

            if (candidateFile.exists()) {
                storeFile = candidateFile
                storeType = "PKCS12"
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                    ?: keystoreProps.getProperty("storePassword")
                    ?: "aegisagent-release"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: keystoreProps.getProperty("keyAlias")
                    ?: "aegisagent"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: keystoreProps.getProperty("keyPassword")
                    ?: "aegisagent-release"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
