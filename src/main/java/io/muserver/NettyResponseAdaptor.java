package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import javax.ws.rs.core.MediaType;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.Future;

import static io.muserver.ContentTypes.TEXT_PLAIN_UTF8;

abstract class NettyResponseAdaptor implements MuResponse {
    protected final boolean isHead;
    protected OutputState outputState = OutputState.NOTHING;
    protected final NettyRequestAdapter request;
    protected ChannelFuture lastAction;
    private final Headers headers;
    protected int status = 200;
    private PrintWriter writer;
    private OutputStream outputStream;
    protected long bytesStreamed = 0;
    protected long declaredLength = -1;

    protected enum OutputState {
        NOTHING, FULL_SENT, STREAMING, STREAMING_COMPLETE, FINISHED, DISCONNECTED, WEBSOCKET
    }

    void setWebsocket() {
        outputState = OutputState.WEBSOCKET;
    }

    void onCancelled() {
        outputState = OutputState.DISCONNECTED;
    }

    NettyResponseAdaptor(NettyRequestAdapter request, Headers headers) {
        this.headers = headers;
        this.request = request;
        this.isHead = request.method() == Method.HEAD;
        this.headers.set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
    }

    public int status() {
        return status;
    }

    public void status(int value) {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot set the status after the headers have already been sent");
        }
        status = value;
    }

    protected void startStreaming() {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot start streaming when state is " + outputState);
        }
        declaredLength = headers.contains(HeaderNames.CONTENT_LENGTH)
            ? Long.parseLong(headers.get(HeaderNames.CONTENT_LENGTH))
            : -1;
        outputState = OutputState.STREAMING;
    }

    static CharSequence getVaryWithAE(String curValue) {
        if (Mutils.nullOrEmpty(curValue)) {
            return HeaderNames.ACCEPT_ENCODING;
        } else {
            if (!curValue.toLowerCase().contains(HeaderNames.ACCEPT_ENCODING)) {
                return curValue + ", " + HeaderNames.ACCEPT_ENCODING;
            } else {
                return curValue;
            }
        }
    }

    private void throwIfFinished() {
        if (outputState == OutputState.FULL_SENT || outputState == OutputState.FINISHED || outputState == OutputState.DISCONNECTED) {
            throw new IllegalStateException("Cannot write data as response has already completed");
        }
    }

    public Future<Void> writeAsync(String text) {
        return write(textToBuffer(text), false);
    }

    ChannelFuture write(ByteBuffer data) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        return write(Unpooled.wrappedBuffer(data), false);
    }

    protected final ChannelFuture write(ByteBuf data, boolean sync) {
        throwIfFinished();
        int size = data.writerIndex();

        bytesStreamed += size;
        ChannelFuture lastAction;
        boolean isLast = bytesStreamed == declaredLength;

        if (declaredLength > -1 && bytesStreamed > declaredLength) {
            onContentLengthMismatch();
            isLast = true;
        }

        if (isLast) {
            outputState = OutputState.FULL_SENT;
        }

        ByteBuf content = Unpooled.wrappedBuffer(data);
        lastAction = writeToChannel(isLast, content);
        if (sync) {
            // force exception if writes fail
            lastAction = lastAction.syncUninterruptibly();
        }
        this.lastAction = lastAction;
        return lastAction;
    }

    protected abstract void onContentLengthMismatch();

    abstract ChannelFuture writeToChannel(boolean isLast, ByteBuf content);

    public void sendChunk(String text) {
        throwIfFinished();
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        lastAction = write(textToBuffer(text), true);
    }

    private ByteBuf textToBuffer(String text) {
        if (text == null) text = "";
        Charset charset = StandardCharsets.UTF_8;
        MediaType type = headers().contentType();
        if (type != null) {
            String encoding = type.getParameters().get("charset");
            if (!Mutils.nullOrEmpty(encoding)) {
                charset = Charset.forName(encoding);
            }
        }
        return Unpooled.copiedBuffer(text, charset);
    }

    public void redirect(String newLocation) {
        redirect(URI.create(newLocation));
    }


    public Headers headers() {
        return headers;
    }

    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    public void addCookie(Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie.nettyCookie));
    }

    public OutputStream outputStream() {
        if (this.outputStream == null) {
            startStreaming();
            this.outputStream = new BufferedOutputStream(new ChunkedHttpOutputStream(this), 4096);
        }
        return this.outputStream;
    }

    public PrintWriter writer() {
        if (this.writer == null) {
            OutputStreamWriter os = new OutputStreamWriter(outputStream(), StandardCharsets.UTF_8);
            this.writer = new PrintWriter(os);
        }
        return this.writer;
    }

    @Override
    public boolean hasStartedSendingData() {
        return outputState != OutputState.NOTHING;
    }

    boolean clientDisconnected() {
        return outputState == OutputState.DISCONNECTED;
    }

    ChannelFuture complete(boolean forceDisconnect) {
        if (outputState == OutputState.FINISHED) {
            return lastAction;
        }
        boolean shouldDisconnect = forceDisconnect || !request.isKeepAliveRequested();
        boolean isFixedLength = headers.contains(HeaderNames.CONTENT_LENGTH);
        if (outputState == OutputState.NOTHING) {
            boolean addContentLengthHeader = ((!isHead || !isFixedLength) && status != 204 && status != 205 && status != 304);
            sendEmptyResponse(addContentLengthHeader);
        } else if (outputState == OutputState.STREAMING) {

            if (!isHead) {
                Mutils.closeSilently(writer);
                Mutils.closeSilently(outputStream);
            }
            boolean badFixedLength = !isHead && isFixedLength && declaredLength != bytesStreamed && status != 304;
            if (badFixedLength) {
                shouldDisconnect = onBadRequestSent();
            }
            lastAction = writeLastContentMarker();
        }

        if (shouldDisconnect) {
            if (lastAction == null) {
                lastAction = closeConnection();
            } else {
                lastAction = lastAction.addListener(ChannelFutureListener.CLOSE);
            }
        }
        if (this.outputState != OutputState.DISCONNECTED) {
            this.outputState = OutputState.FINISHED;
        }
        return lastAction;
    }

    /**
     * Called when the number of bytes declared is different from the number sent
     *
     * @return True to disconnect the connection; otherwise false
     */
    protected abstract boolean onBadRequestSent();

    @Override
    public void write(String text) {
        throwIfFinished();
        if (outputState != OutputState.NOTHING) {
            String what = outputState == OutputState.FULL_SENT ? "twice for one response" : "after sending chunks";
            throw new IllegalStateException("You cannot call write " + what + ". If you want to send text in multiple chunks, use sendChunk instead.");
        }
        outputState = OutputState.FULL_SENT;
        ByteBuf body = textToBuffer(text);
        long bodyLength = body.writerIndex();

        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers.set(HeaderNames.CONTENT_TYPE, TEXT_PLAIN_UTF8);
        }
        headers.set(HeaderNames.CONTENT_LENGTH, bodyLength);

        writeFullResponse(body);
    }

    protected abstract void writeFullResponse(ByteBuf body);

    protected abstract ChannelFuture closeConnection();

    protected abstract boolean connectionOpen();

    protected abstract ChannelFuture writeLastContentMarker();

    public final void redirect(URI newLocation) {
        URI absoluteUrl = request.uri().resolve(newLocation);
        if (status < 300 || status > 303) {
            status(302);
        }
        headers.set(HeaderNames.LOCATION, absoluteUrl.toString());
        headers.set(HeaderNames.CONTENT_LENGTH, HeaderValues.ZERO);

        writeRedirectResponse();

        outputState = OutputState.FULL_SENT;
    }

    protected abstract void writeRedirectResponse();

    protected abstract void sendEmptyResponse(boolean addContentLengthHeader);

    HttpResponseStatus httpStatus() {
        return HttpResponseStatus.valueOf(status());
    }

    static class EmptyHttpResponse extends DefaultFullHttpResponse {
        EmptyHttpResponse(HttpResponseStatus status) {
            super(HttpVersion.HTTP_1_1, status, false);
        }
    }

}
