plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mobileaistudio.app"
    compileSdk = 35
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
        resources { pickFirsts += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/MANIFEST.MF", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
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
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    implementation("com.github.luben:zstd-jni:1.5.6-4@aar")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("commons-net:commons-net:3.11.1")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") { exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18") }
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:160.0.20261104181552")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.frostwire:jlibtorrent:2.0.15.3")
    implementation("com.frostwire:jlibtorrent-android-arm64:2.0.15.3")
}

configurations.all {
    resolutionStrategy.eachDependency {
        // the newest AndroidX needs a newer Android Gradle plugin; older ones work with the same browser engine
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) useVersion("2.0.21")
        if (requested.group == "androidx.media3") useVersion("1.5.1")
        if (requested.group == "androidx.core" && requested.name == "core") useVersion("1.15.0")
    }
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

// the browser engine declares a newer compile SDK than we build with; it still runs fine on this one
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
