plugins {
}

android {
    namespace = "com.rcdriving.photosync"

    defaultConfig {
        applicationId = "com.rcdriving.photosync"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
}