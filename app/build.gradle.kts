import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 署名鍵の情報は keystore.properties(gitignore済み)から読む。
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "jp.akihub.devicesetup"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.akihub.devicesetup"
        minSdk = 28
        targetSdk = 34
        versionCode = 14
        versionName = "0.5.8"
    }

    signingConfigs {
        create("release") {
            if (ksProps.isNotEmpty()) {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 外部ライブラリなし(Android標準APIのみ)。テストだけJUnit。
    testImplementation("junit:junit:4.13.2")
}
