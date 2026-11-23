// OpenRAC multiplayer backend: the wire protocol, the hub and the server.
rootProject.name = "openrac-server"

pluginManagement {
    // Convention plugins live in an included build beside the modules.
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the Java 25 toolchain when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The modules live under services/ with the paths :common, :hub and :server.
include("common", "hub", "server")
project(":common").projectDir = file("services/common")
project(":hub").projectDir = file("services/hub")
project(":server").projectDir = file("services/server")
