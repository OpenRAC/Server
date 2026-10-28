// Shared code: the wire protocol (packet ids, opcodes, buffers, codecs, pipeline), the logger
// and the environment.
plugins {
    id("openrac.java-conventions")
    `java-library`
}

dependencies {
    // Exposed so consumers compile against the pipeline's Netty types.
    api(libs.netty.codec.base)

    // Reads .env.defaults and .env for the environment.
    implementation(libs.dotenv.java)
}
