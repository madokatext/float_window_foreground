plugins {
    id("com.android.application") version "9.1.0"
}

android {
    namespace = "io.github.madokatext.floatwindowforeground"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.madokatext.floatwindowforeground"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(files("libs/libxposed-api100.jar"))
}
