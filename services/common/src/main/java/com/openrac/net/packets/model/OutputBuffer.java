package com.openrac.net.packets.model;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A growable byte buffer for outgoing packet data. It writes primitives and strings in big-endian
 * order, the encoding {@link InputBuffer} reads.
 */

public class OutputBuffer extends ByteArrayOutputStream {
    /**
     * Appends the bytes of another buffer to the end of this one.
     * @param other The buffer to append.
     * @return This buffer, for chaining.
     */

    public OutputBuffer append(OutputBuffer other) {
        try {
            other.writeTo(this);
        } catch (IOException ignored) {
            // Writing into memory never throws.
        }

        return this;
    }

    /**
     * Copies this buffer's bytes onto the end of a Netty buffer, with no array in between.
     * @param target The Netty buffer to append to.
     */

    public synchronized void writeTo(ByteBuf target) {
        target.writeBytes(buf, 0, count);
    }

    /**
     * Writes an unsigned byte. A value outside 0 to 255 is written as 0.
     * @param value The unsigned byte to write, in integer form.
     */

    public void writeUnsignedByte(int value) {
        // Out of range has no byte to become.
        if (value < 0 || value > 255) value = 0;

        write(value);
    }

    /**
     * Writes a short in big-endian order.
     * @param value The short to write.
     */

    public void writeShort(short value) {
        write(value >> 8);
        write(value);
    }

    /**
     * Writes an int in big-endian order.
     * @param value The int to write.
     */

    public void writeInt(int value) {
        write(value >> 24);
        write(value >> 16);
        write(value >> 8);
        write(value);
    }

    /**
     * Writes a long in big-endian order.
     * @param value The long to write.
     */

    public void writeLong(long value) {
        writeInt((int) (value >> 32));
        writeInt((int) value);
    }

    /**
     * Writes a UTF-8 string behind a short length header.
     * @param value The string to write.
     */

    public void writeString(String value) {
        // A null or empty string writes -1 so the reader skips it.
        if (value == null || value.isEmpty()) {
            writeShort((short) -1);
            return;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        writeShort((short) bytes.length);
        writeBytes(bytes);
    }
}
