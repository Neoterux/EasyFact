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

    testOptions {
        managedDevices {
            // The device matrix behind NFR-07 (D-22): the golden-byte test runs on API 31 (the minSdk),
            // API 34 and the latest the project targets, and must produce the JVM's bytes on each. It is
            // run with `./gradlew :app:verifyDeviceMatrix`, which is deliberately not attached to `check`
            // (see docs/build-invariants.md).
            //
            // Image source: `aosp` first, `google` as the recorded fallback. `aosp` is the plain
            // "default" arm64 image; `google` is the Google APIs image, larger and slower to provision.
            // Select it with `-Peasyfact.deviceImageSource=google` when an `aosp` image cannot be
            // provisioned on the host, and record the substitution; it applies to every device at once
            // so a run never mixes sources silently.
            //
            // The ATD ("automated test device") images are not an option for this matrix: they exist
            // only for API 30 and below for plain use, and no level here is served by them, so do not
            // "optimise" the matrix onto them.
            //
            // A host without hardware GPU acceleration (a CI runner) must pass
            // `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`; it is not set
            // here because it slows an accelerated host down.
            val imageSource = providers.gradleProperty("easyfact.deviceImageSource").getOrElse("aosp")
            require(imageSource == "aosp" || imageSource == "google") {
                "easyfact.deviceImageSource must be 'aosp' or 'google', not '$imageSource'"
            }
            val matrixApiLevels = listOf(31, 34, 37)
            localDevices {
                for (api in matrixApiLevels) {
                    create("api$api") {
                        device = "Pixel 6"
                        apiLevel = api
                        systemImageSource = imageSource
                    }
                }
            }
            groups {
                create("matrix") {
                    for (api in matrixApiLevels) targetDevices.add(localDevices["api$api"])
                }
            }
        }
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