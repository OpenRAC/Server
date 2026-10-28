// Shared wire protocol: packet ids, opcodes, buffers, codecs and the pipeline.
plugins {
    id("openrac.java-conventions")
    `java-library`
}

dependencies {
    // Exposed so consumers compile against the pipeline's Netty types.
    api(libs.netty.codec.base)
}
