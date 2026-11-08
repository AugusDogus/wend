plugins { id("com.android.application") }
android {
    namespace = "dev.augie.wend"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "dev.augie.wend"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "WINDOWED_MAPS", "false")
        buildConfigField("String", "MAPS_PACKAGE", "\"com.google.android.apps.maps\"")
        buildConfigField("String", "VIDEO_PACKAGE", "\"com.google.android.youtube\"")
    }
    buildTypes {
        create("windowed") {
            initWith(getByName("release"))
            buildConfigField("boolean", "WINDOWED_MAPS", "true")
        }
        create("emulator") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".emulator"
            buildConfigField("String", "MAPS_PACKAGE", "\"dev.augie.fixture.maps\"")
            buildConfigField("String", "VIDEO_PACKAGE", "\"dev.augie.fixture.video\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { disable += "ExpiredTargetSdkVersion" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    compileOnly(project(":platform-stubs"))
    testImplementation(project(":platform-stubs"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
