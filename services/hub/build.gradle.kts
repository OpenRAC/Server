// The hub players reach first: the game server list and the accounts.
plugins {
    id("openrac.java-conventions")
    `java-library`
    application
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("com.openrac.hub.Hub")
}

dependencies {
    api(project(":common"))

    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.bcrypt)
}

tasks {
    // Run from the repository root, where the .env files are.
    named<JavaExec>("run") {
        workingDir = rootProject.projectDir
    }

    // Flyway finds its PostgreSQL support through service files, so they are merged.
    shadowJar {
        archiveBaseName.set("openrac-hub")
        archiveVersion.set("1.0.0")
        archiveClassifier.set("")
        // The merge only sees every copy when duplicates reach it.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
    }

    // One jar with every dependency inside, run by scripts/run_hub.sh.
    register("buildHubJar") {
        group = "build"
        description = "Builds the hub jar."
        dependsOn(shadowJar)
    }
}
