pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "RoadHome"
include(":app", ":platform-stubs", ":video-probe", ":task-fixture")
