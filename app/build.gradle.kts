plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mobileaistudio.app"
    compileSdk = 34
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.mobileaistudio.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    // One fixed key, so a new build installs over the old one and keeps your projects.
    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "mobileai"
            keyAlias = "mobileai"
            keyPassword = "mobileai"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // the CPU variants are loaded by file path, so they must be unpacked on install
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.frostwire:jlibtorrent:2.0.15.3")
    implementation("com.frostwire:jlibtorrent-android-arm64:2.0.15.3")
}
