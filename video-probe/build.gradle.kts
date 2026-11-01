plugins { id("com.android.application") }
android {
    namespace = "dev.augie.videoprobe"
    compileSdk = 35
    defaultConfig { minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    buildFeatures { aidl = true }
    flavorDimensions += "role"
    productFlavors {
        create("source") { dimension = "role"; applicationId = "dev.augie.videoprobe.source" }
        create("receiver") { dimension = "role"; applicationId = "dev.augie.videoprobe.receiver" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { disable += "ExpiredTargetSdkVersion" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
