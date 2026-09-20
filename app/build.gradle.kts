plugins {
    id("com.android.application")
}

android {
    namespace = "com.a01mirror.dlna"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.a01mirror.dlna"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // TAndroidLame is a native dependency. Legacy packaging is intentionally used
        // to avoid APK-installation issues on devices that inspect native-library
        // zip alignment, while the app remains buildable on current AGP.
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    // MPEG-1 Layer III is used because the target STP-A01 documentation lists MPEG-1/2 Layer I/II/III decoding.
    implementation("com.github.naman14:TAndroidLame:1.1")

    testImplementation("junit:junit:4.13.2")
}
