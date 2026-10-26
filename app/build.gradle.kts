plugins {
    alias(libs.plugins.android.application)
    id("easyfact.app-gates")
}

android {
    namespace = "com.neoterux.easyfact"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.neoterux.easyfact"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Recorded answer (D-05): the optimization-scoping entry (packageScope) that the
            // project skeleton shipped is deliberately absent. Measured on the unmodified
            // skeleton, it left the app's own code and every library outside the listed packages
            // unshrunk and un-inlined (an unused probe class survived into the release dex and
            // mapping.txt listed no app class): release APK 3,776,803 bytes with it versus
            // 883,902 without, App Bundle 2,536,417 versus 737,208. The official guidance is not
            // to use it for apps that already run R8, so R8 full mode covers the whole app.
            // Measurements: 01-RESEARCH.md, Pitfall 1.
            // `enable = true` turns on both code minification and resource shrinking in AGP 9.3+;
            // easyfact.app-gates fails the build if either is ever off.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(project(":core:invoicing"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}