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
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    // LAME MP3 encoder; the STP-A01 is documented with MPEG-1/2 Layer I/II/III audio decoding.
    implementation("com.github.naman14:TAndroidLame:1.1")
}
