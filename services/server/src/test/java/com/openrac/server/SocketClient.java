package com.openrac.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;

import com.openrac.net.packets.Opcodes;
import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.InputBuffer;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.out.HelloPacket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;

/**
 * One real TCP connection to a service on this machine, framing packets as a game client does.
 * Reads wait at most ten seconds, so a missing answer fails the test instead of hanging it.
 */

class SocketClient implements AutoCloseable {
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    /**
     * Connects to a port on the loopback address.
     * @param port The service's port.
     */

    SocketClient(int port) {
        try {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Closes the connection.
     */

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Frames a packet with its length and sends it.
     * @param packet The packet to send.
     */

    void send(OutgoingPacket packet) {
        ByteBuf frame = Unpooled.buffer();

        frame.writeInt(packet.size());
        packet.writeTo(frame);

        byte[] bytes = new byte[frame.readableBytes()];

        frame.readBytes(bytes);
        frame.release();

        try {
            output.write(bytes);
            output.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Reads the next packet and checks its id.
     * @param expected The packet expected next.
     * @return The payload after the id.
     */

    InputBuffer receive(Packets expected) {
        try {
            byte[] bytes = new byte[input.readInt()];

            input.readFully(bytes);

            InputBuffer payload = new InputBuffer(bytes);

            Assertions.assertEquals(expected, Packets.getFromByte(payload.readUnsignedByte()));

            return payload;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * @return The reason of the next packet, which must be an error.
     */

    Opcodes.Error receiveError() {
        return Opcodes.Error.get(receive(Packets.ERROR).readUnsignedByte());
    }

    /**
     * Says hello, then reads the session id and the UDP offer.
     * @param name The display name.
     * @return The session id.
     */

    int hello(String name) {
        send(new HelloPacket("0.1.0", name));

        int id = receive(Packets.HELLO).readInt();

        receive(Packets.UDP);

        return id;
    }
}
