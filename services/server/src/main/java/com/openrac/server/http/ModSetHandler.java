package com.openrac.server.http;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import com.openrac.server.Constants;
import com.openrac.net.limits.ConnectionLimit;
import com.openrac.utils.logger.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Answers the mod set routes: PUT /v1/modsets stores a set and answers its code, GET
 * /v1/modsets/CODE answers the set. One request per connection, so connections stay short.
 */

public class ModSetHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String ROUTE = "/v1/modsets";

    private final ModSetStore store;
    private final HttpRateLimits limits;

    private boolean counted; // Whether the connection limit counted this connection.

    /**
     * @param store The sets.
     * @param limits The rates every connection shares.
     */

    public ModSetHandler(ModSetStore store, HttpRateLimits limits) {
        this.store = store;
        this.limits = limits;
    }

    /**
     * Counts the connection against its address, as a game connection is.
     * @param context The channel's context.
     */

    @Override
    public void channelActive(ChannelHandlerContext context) {
        counted = ConnectionLimit.open(address(context), Constants.MAX_CONNECTIONS_PER_ADDRESS);

        // Over the limit, nothing is answered.
        if (!counted) {
            context.close();
            return;
        }

        // A deadline for the whole connection: a trickle of bytes must not hold it open.
        context.executor().schedule(() -> context.close(), Constants.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Forgets the connection.
     * @param context The channel's context.
     */

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (counted) ConnectionLimit.close(address(context));
    }

    /**
     * Routes a request.
     * @param context The channel's context.
     * @param request The whole request.
     */

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        String path = request.uri().split("\\?", 2)[0];

        // A bad request has no route to answer it.
        if (!request.decoderResult().isSuccess()) {
            respond(context, HttpResponseStatus.BAD_REQUEST, new byte[0], "text/plain");
            return;
        }

        if (path.equals(ROUTE)) {
            // Storing is the one thing the bare route does.
            if (request.method().equals(HttpMethod.PUT)) handlePut(context, request);
            else respond(context, HttpResponseStatus.METHOD_NOT_ALLOWED, new byte[0], "text/plain");

            return;
        }

        if (path.startsWith(ROUTE + "/")) {
            // Sets never change once stored, so only reading is allowed.
            if (request.method().equals(HttpMethod.GET)) handleGet(context, path.substring(ROUTE.length() + 1));
            else respond(context, HttpResponseStatus.METHOD_NOT_ALLOWED, new byte[0], "text/plain");

            return;
        }

        respond(context, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain");
    }

    /**
     * Stores a set after checking it.
     * @param context The channel's context.
     * @param request The request, its body the compressed set.
     */

    private void handlePut(ChannelHandlerContext context, FullHttpRequest request) {
        // Storing costs memory, so its rate is the lower one.
        if (!limits.takePut(address(context))) {
            respond(context, HttpResponseStatus.TOO_MANY_REQUESTS, new byte[0], "text/plain");
            return;
        }

        byte[] body = ByteBufUtil.getBytes(request.content());

        try {
            String hash = ModSetValidator.check(body);
            String code = store.put(body, hash);
            byte[] answer = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);

            respond(context, HttpResponseStatus.OK, answer, "application/json");
        } catch (InvalidModSetException exception) {
            Log.debug(address(context) + " sent an invalid mod set (" + exception.getMessage() + ").");
            respond(context, HttpResponseStatus.BAD_REQUEST, new byte[0], "text/plain");
        }
    }

    /**
     * Answers a set.
     * @param context The channel's context.
     * @param code The code, in any case, with or without its dash.
     */

    private void handleGet(ChannelHandlerContext context, String code) {
        // The rate is what keeps guessing codes pointless.
        if (!limits.takeGet(address(context))) {
            respond(context, HttpResponseStatus.TOO_MANY_REQUESTS, new byte[0], "text/plain");
            return;
        }

        byte[] body = store.get(code.replace("-", "").toUpperCase());

        // The launcher tells the player to ask for a new code.
        if (body == null) {
            respond(context, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain");
            return;
        }

        respond(context, HttpResponseStatus.OK, body, "application/octet-stream");
    }

    /**
     * Answers and closes the connection.
     * @param context The channel's context.
     * @param status The status.
     * @param body The body.
     * @param type The body's content type.
     */

    private void respond(ChannelHandlerContext context, HttpResponseStatus status, byte[] body, String type) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONNECTION, "close");

        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Closes a connection that failed.
     * @param context The channel's context.
     * @param cause What went wrong.
     */

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        Log.warning("Error on the mod set port from " + address(context) + ", closing: " + cause.getMessage() + ".");

        context.close();
    }

    /**
     * @param context The channel's context.
     * @return The other end's address.
     */

    private static InetAddress address(ChannelHandlerContext context) {
        return ((InetSocketAddress) context.channel().remoteAddress()).getAddress();
    }
}
