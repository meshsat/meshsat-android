pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (RNode over USB OTG, MESHSAT-1553); jitpack is on F-Droid's allowed list
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MeshSat"
include(":app")
