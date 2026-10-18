plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "be.kasugi.netflop"
    compileSdk = 35

    defaultConfig {
        applicationId = "be.kasugi.netflop"
        minSdk = 24
        // 29 = accès "classique" aux fichiers du disque USB sur la Shield (Android 11)
        targetSdk = 29
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("netflop") {
            storeFile = file("netflop.keystore")
            storePassword = "netflop"
            keyAlias = "netflop"
            keyPassword = "netflop"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("netflop")
        }
        debug {
            signingConfig = signingConfigs.getByName("netflop")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.annotation:annotation-experimental:1.4.1")

    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Décodage logiciel DTS / TrueHD / etc. si la télé ne les accepte pas en passthrough
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    implementation("io.coil-kt:coil-compose:2.7.0")
}
