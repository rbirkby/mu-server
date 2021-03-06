package io.muserver;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class GrowableByteBufferInputStream extends InputStream {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    private static final ByteBuffer LAST = ByteBuffer.allocate(0);
    private final BlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>();
    private volatile ByteBuffer current = EMPTY;
    private RequestBodyListener listener;
    private final Object listenerLock = new Object();

    private final long readTimeoutMillis;
    private final long maxSize;
    private final AtomicLong bytesRead = new AtomicLong(0);

    GrowableByteBufferInputStream(long readTimeoutMillis, long maxSize) {
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxSize = maxSize;
    }

    private ByteBuffer cycleIfNeeded() throws IOException {
        if (current == LAST) {
            return current;
        }
        synchronized (queue) {
            ByteBuffer cur = current;
            if (!cur.hasRemaining()) {
                try {
                    current = queue.poll(readTimeoutMillis, TimeUnit.MILLISECONDS);
                    cur = current;
                } catch (InterruptedException e) {
                    // given the InputStream API, is this the way to handle interuptions?
                    throw new IOException("Thread was interrupted");
                }
            }
            return cur;
        }
    }

    public int read() throws IOException {
        ByteBuffer cur = cycleIfNeeded();
        if (cur == LAST) {
            return -1;
        }
        return cur.get() & 0xff;
    }

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        ByteBuffer cur = cycleIfNeeded();
        if (cur == LAST) {
            return -1;
        }
        int toRead = Math.min(len, cur.remaining());
        cur.get(b, off, toRead);
        return toRead;
    }

    public int available() throws IOException {
        ByteBuffer cur = cycleIfNeeded();
        return cur.remaining();
    }

    public void close() throws IOException {
        // This is called from the main netty accepter thread so must be non-blocking
        synchronized (listenerLock) {
            if (listener == null) {
                queue.add(LAST);
            } else {
                sendToListener(listener, LAST, DoneCallback.NoOp);
            }
        }
    }

    void handOff(ByteBuf data, DoneCallback doneCallback) {
        // This is called from the main netty accepter thread so must be non-blocking
        synchronized (listenerLock) {
            long read = bytesRead.addAndGet(data.readableBytes());
            if (read > maxSize) {
                throw new MuException();
            }
            if (listener == null) {
                ByteBuf copy = data.copy();
                ByteBuffer byteBuffer = ByteBuffer.allocate(data.capacity());
                copy.readBytes(byteBuffer).release();
                byteBuffer.flip();
                queue.add(byteBuffer);
                try {
                    // TODO: only call done when data is used so it doesn't need to be copied
                    doneCallback.onComplete(null);
                } catch (Exception ignored) {
                }
            } else {
                sendToListener(listener, data.nioBuffer(), doneCallback);
            }
        }
    }

    void switchToListener(RequestBodyListener readListener) {
        synchronized (listenerLock) {
            while (!queue.isEmpty()) {
                ArrayList<ByteBuffer> existing = new ArrayList<>(queue.size());
                queue.drainTo(existing);
                for (ByteBuffer byteBuffer : existing) {
                    sendToListener(readListener, byteBuffer, DoneCallback.NoOp);
                }
            }
            this.listener = readListener;
        }
    }

    private static void sendToListener(RequestBodyListener readListener, ByteBuffer byteBuffer, DoneCallback doneCallback) {
        if (byteBuffer == LAST) {
            readListener.onComplete();
        } else {
            try {
                readListener.onDataReceived(byteBuffer, error -> {
                    doneCallback.onComplete(error);
                    if (error != null) {
                        readListener.onError(error);
                    }
                });
            } catch (Exception e) {
                readListener.onError(e);
            }
        }
    }
}
