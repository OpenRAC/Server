// Included build that holds the openrac.* convention plugins.
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    // Shares the root version catalog with the convention plugins.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
