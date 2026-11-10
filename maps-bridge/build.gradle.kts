plugins { id("com.android.application") }
android {
    namespace = "dev.augie.wend.mapsbridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.augie.wend.mapsbridge"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { disable += "ExpiredTargetSdkVersion" }
}
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
