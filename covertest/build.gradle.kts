plugins {
    id("com.android.application")
}

android {
    namespace = "dev.flipfix.covertest"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.flipfix.covertest"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
}
