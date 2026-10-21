plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.mobilestudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mobilestudio"
        minSdk = 26
        // Deliberately 28 (like Termux): from API 29 on, an app may not execute files from its own storage,
        // and the build runs the imported aapt2 binary and terminal commands. 28 also keeps classic
        // java.io access to shared storage for "Open folder". Not suitable for Play Store publishing.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // If aapt2 is bundled as jniLibs/<abi>/libaapt2.so it must be extracted to the native library dir.
        jniLibs { useLegacyPackaging = true }
        // The bundled compilers (ECJ, R8, Kotlin) overlap in META-INF; these rules resolve the usual clashes.
        resources {
            excludes += setOf(
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA",
            )
            merges += setOf("META-INF/services/**")
            pickFirsts += setOf("META-INF/*.kotlin_module", "META-INF/MANIFEST.MF", "about.html", "plugin.properties", "META-INF/eclipse.inf")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.android.tools.build:apksig:8.7.3")

    // On-device build tools. Large (tens of MB) and not yet run on a device; see README.
    implementation("org.eclipse.jdt:ecj:3.33.0")
    implementation("com.android.tools:r8:8.7.18")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
