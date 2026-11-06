package com.btl.auction.server.network;

import com.btl.auction.common.protocol.JsonCodec;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientSession implements AutoCloseable {
    private record Outbound(String line, CountDownLatch written) {
    }

    private final Socket socket;
    private final ArrayBlockingQueue<Outbound> outbound = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Thread writer;

    public ClientSession(Socket socket) {
        this.socket = socket;
        writer = new Thread(this::writeLoop, "auction-client-writer");
        writer.setDaemon(true);
        writer.start();
    }

    public Socket socket() {
        return socket;
    }

    public boolean send(Object message) {
        if (!open.get()) {
            return false;
        }
        boolean queued = outbound.offer(new Outbound(JsonCodec.toJson(message), null));
        if (!queued) {
            close();
        }
        return queued;
    }

    public void sendFinal(Object message) {
        CountDownLatch written = new CountDownLatch(1);
        if (open.get() && outbound.offer(new Outbound(JsonCodec.toJson(message), written))) {
            try {
                written.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        close();
    }

    private void writeLoop() {
        try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (open.get()) {
                Outbound message = outbound.take();
                output.write(message.line());
                output.newLine();
                output.flush();
                if (message.written() != null) {
                    message.written().countDown();
                }
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket is already closing.
            }
            writer.interrupt();
        }
    }
}
