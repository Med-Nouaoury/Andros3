plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

android {
    namespace = "net.rpcs3"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.mednouaoury.andros3"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_JOB_POOLS=compile=8;link=1",
                    "-DCMAKE_JOB_POOL_COMPILE=compile",
                    "-DCMAKE_JOB_POOL_LINK=link"
                )
            }
        }
    }

    signingConfigs {
        // A fixed, committed debug key: without it every CI run signs with a
        // fresh key, so a new debug APK cannot be installed over the old one and
        // users lose firmware, games and compiled caches on each update.
        // This key is public on purpose; release builds use the custom key below.
        getByName("debug") {
            storeFile = file("andros3-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("custom-key") {
            val keystoreAlias = System.getenv("KEYSTORE_ALIAS") ?: ""
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""

            if (keystorePath.isNotEmpty() && file(keystorePath).exists() && file(keystorePath).length() > 0) {
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
                storeFile = file(keystorePath)
                storePassword = keystorePassword
            } else {
                val debugKeystoreFile = file("${System.getProperty("user.home")}/debug.keystore")

                println("⚠️ Custom keystore not found or empty! creating debug keystore.")

                if (!debugKeystoreFile.exists()) {
                    Runtime.getRuntime().exec(
                        arrayOf(
                            "keytool", "-genkeypair",
                            "-v", "-keystore", debugKeystoreFile.absolutePath,
                            "-storepass", "android",
                            "-keypass", "android",
                            "-alias", "androiddebugkey",
                            "-keyalg", "RSA",
                            "-keysize", "2048",
                            "-validity", "10000",
                            "-dname", "CN=Android Debug,O=Android,C=US"
                        )
                    ).waitFor()
                }

                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeFile = debugKeystoreFile
                storePassword = "android"
            }
        }
    }

    flavorDimensions += "branding"

    productFlavors {
        // Andros3 ships under its own package name only. The upstream fork also
        // built copies under other apps' package names (benchmark/game IDs) to
        // trigger vendor performance governors; that is dropped here.
        create("standard") {
            dimension = "branding"
            applicationId = "io.github.mednouaoury.andros3"
        }
    }

    buildTypes {
        debug {
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_BUILD_TYPE=RelWithDebInfo")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("custom-key") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }


    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    sourceSets["main"].assets.srcDir(
        files(layout.buildDirectory.dir("generated/rpcs3-assets"))
            .builtBy("copyRpcs3Icons", "copyRpcs3Patches")
    )

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
    }
}

base.archivesName = "ps3native"

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui.tooling.preview.android)
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-android:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-json:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-client-logging:3.0.3")
}

val copyRpcs3Icons by tasks.registering(Copy::class) {
    from(file("../../bin/Icons"))
    into(layout.buildDirectory.dir("generated/rpcs3-assets/Icons"))
}

val copyRpcs3Patches by tasks.registering(Copy::class) {
    from(file("../../bin/patches")) {
        include("patch.yml")
    }
    into(layout.buildDirectory.dir("generated/rpcs3-assets/patches"))
}

// The generated assets dir is registered with builtBy(), but AGP does not
// follow that dependency, so these copies never ran in clean CI builds and the
// APK shipped without overlay icons or the bundled patch.yml. Run them before
// anything else in the build.
tasks.named("preBuild") {
    dependsOn(copyRpcs3Icons, copyRpcs3Patches)
}
