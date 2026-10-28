package com.openrac.net.packets.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Reads the big-endian encoding written by {@link OutputBuffer}. Reading past the end throws a
 * {@link java.nio.BufferUnderflowException}, which the packet handler treats as a malformed packet.
 * @param buffer The underlying byte buffer that the reads consume.
 */

public record InputBuffer(ByteBuffer buffer) {
    /**
     * Wraps a raw byte array into a new input buffer.
     * @param bytes The raw bytes to wrap.
     */

    public InputBuffer(byte[] bytes) {
        this(ByteBuffer.wrap(bytes));
    }

    /**
     * Reads one byte as an unsigned value.
     * @return The byte, between 0 and 255.
     */

    public int readUnsignedByte() {
        return Byte.toUnsignedInt(buffer.get());
    }

    /**
     * Reads an int from the buffer.
     * @return The int read from the buffer.
     */

    public int readInt() {
        return buffer.getInt();
    }

    /**
     * Reads a long from the buffer.
     * @return The long read from the buffer.
     */

    public long readLong() {
        return buffer.getLong();
    }

    /**
     * Reads a UTF-8 string behind a short length header.
     * @return The string, or an empty string when the header marks it empty.
     */

    public String readString() {
        short length = buffer.getShort();

        // The writer marks an empty string with -1.
        if (length < 1) return "";

        byte[] bytes = new byte[length];

        buffer.get(bytes);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads every byte left in the buffer.
     * @return The remaining bytes.
     */

    public byte[] readRemainingBytes() {
        byte[] bytes = new byte[buffer.remaining()];

        buffer.get(bytes);

        return bytes;
    }

    /**
     * @return The number of bytes left to read.
     */

    public int size() {
        return buffer.remaining();
    }
}
