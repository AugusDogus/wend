plugins { id("com.android.application") }
android {
    namespace = "dev.augie.fixture"
    compileSdk = 35
    defaultConfig { minSdk = 29; targetSdk = 29; versionCode = 1; versionName = "0.1" }
    buildFeatures { buildConfig = true }
    flavorDimensions += "role"
    productFlavors {
        create("maps") {
            dimension = "role"
            applicationId = "dev.augie.fixture.maps"
            buildConfigField("boolean", "VIDEO", "false")
            resValue("string", "app_name", "Map test fixture")
        }
        create("video") {
            dimension = "role"
            applicationId = "dev.augie.fixture.video"
            buildConfigField("boolean", "VIDEO", "true")
            resValue("string", "app_name", "Video test fixture")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { disable += "ExpiredTargetSdkVersion" }
}
