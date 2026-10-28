// Java conventions shared by every module: the Java 25 toolchain, UTF-8 sources and JUnit.
plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    // String-keyed configuration names keep this inside a precompiled script plugin.
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Tests run from the repository root, where the .env files are.
    workingDir = rootProject.projectDir
}
