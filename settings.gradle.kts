// OpenRAC multiplayer backend: the shared wire protocol and the server that runs it.
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

// The modules live under services/ but keep the project paths :common and :server.
include("common", "server")
project(":common").projectDir = file("services/common")
project(":server").projectDir = file("services/server")
