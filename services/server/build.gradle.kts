// The multiplayer server: links players into parties and forwards their traffic.
plugins {
    id("openrac.java-conventions")
    application
}

application {
    mainClass.set("com.openrac.server.Server")
}

dependencies {
    implementation(project(":common"))
}

tasks {
    // One jar with every dependency inside, run by scripts/run_server.sh.
    register<Jar>("buildServerJar") {
        group = "build"
        description = "Builds the server jar."
        archiveBaseName.set("openrac-server")
        archiveVersion.set("1.0.0")
        archiveClassifier.set("")

        // Resolving the runtime classpath compiles :common, so depend on it explicitly.
        dependsOn(configurations.runtimeClasspath)

        // The server's own classes and resources.
        from(sourceSets["main"].output)

        // Each runtime dependency's classes, unpacked into the jar.
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            configurations.runtimeClasspath.get()
                .filter { it.name.endsWith(".jar") }
                .map { zipTree(it) }
        }) {
            // Signature files would no longer match the merged jar.
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/SIG-*.SF", "META-INF/SIG-*.RSA")
        }

        manifest {
            attributes["Main-Class"] = "com.openrac.server.Server"
        }
    }
}
