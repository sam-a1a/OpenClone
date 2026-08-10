plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sam.openclone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sam.openclone"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // Only English resources are used; drops every other locale that the
        // AndroidX artifacts ship, which is pure dead weight in the APK.
        androidResources {
            localeFilters += "en"
        }
    }

    buildTypes {
        release {
            optimization {
                // AGP 9.3+: turns on R8 code shrinking *and* resource shrinking.
                enable = true
            }
            // OpenClone is sideloaded, never shipped through Play. Signing the
            // release build with the local debug key means `assembleRelease`
            // emits an APK that installs as-is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Nothing here needs generated BuildConfig/resValues/AIDL, and leaving them
    // on costs build time and a little APK space.
    buildFeatures {
        compose = true
        buildConfig = false
        resValues = false
        aidl = false
        shaders = false
    }

    // The dependency-metadata blob is only useful for Play Console reporting and
    // just bloats a sideloaded APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/META-INF/com/android/build/gradle/*",
                "/kotlin/**",
                "/DebugProbesKt.bin",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        // The APK rewriter is hot-loop code over byte arrays; the implicit
        // null-check preamble Kotlin emits on every public function buys us
        // nothing here and costs both size and cycles.
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
