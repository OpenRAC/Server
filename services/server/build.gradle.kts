// The game server: parties and their traffic, listed by a hub.
plugins {
    id("openrac.java-conventions")
    application
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("com.openrac.server.Server")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":hub"))
    implementation(libs.netty.codec.http)
    implementation(libs.gson)
}

tasks {
    // Run from the repository root, where the .env files are.
    named<JavaExec>("run") {
        workingDir = rootProject.projectDir
    }

    // Flyway, through the hub, finds its PostgreSQL support through service files.
    shadowJar {
        archiveBaseName.set("openrac-server")
        archiveVersion.set("1.0.0")
        archiveClassifier.set("")
        // The merge only sees every copy when duplicates reach it.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
    }

    // One jar with every dependency inside, run by scripts/run_server.sh.
    register("buildServerJar") {
        group = "build"
        description = "Builds the server jar."
        dependsOn(shadowJar)
    }
}
