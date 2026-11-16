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
    implementation("org.luckypray:dexkit:2.2.0")
    // DexKit's API is Kotlin's; its types are needed to compile against it.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    testImplementation("junit:junit:4.13.2")
}
