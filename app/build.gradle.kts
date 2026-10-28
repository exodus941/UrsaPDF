import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PversionName=1.2.3 from the git tag.
val vName = (findProperty("versionName") as String?) ?: "1.5.2"
val vCode = vName.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it[2] }

val ksProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(k: String): String? = System.getenv(k) ?: ksProps.getProperty(k)

android {
    namespace = "com.ursapdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ursapdf"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName
        buildConfigField("String", "UPDATE_REPO", "\"exodus941/UrsaPDF\"")
    }

    val ksPath = secret("URSA_KS_PATH")
    signingConfigs {
        if (ksPath != null) create("release") {
            storeFile = rootProject.file(ksPath)
            storePassword = secret("URSA_KS_PASS")
            keyAlias = "ursa"
            keyPassword = secret("URSA_KS_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
