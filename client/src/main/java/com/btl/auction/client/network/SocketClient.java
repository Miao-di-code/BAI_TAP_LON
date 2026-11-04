package com.btl.auction.client.network;

import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.model.AuctionStatus;
import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.common.protocol.JsonCodec;
import com.btl.auction.common.protocol.LineFraming;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.RequestType;
import com.btl.auction.common.protocol.ResponseMessage;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SocketClient implements AutoCloseable {
    private final ConcurrentHashMap<String, CompletableFuture<ResponseMessage>> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AuctionDto> latestAuctions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<EventMessage>> eventListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Throwable>> disconnectListeners =
            new CopyOnWriteArrayList<>();
    private final ArrayBlockingQueue<String> outgoing = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Gson gson = new Gson();
    private Socket socket;
    private Thread reader;
    private Thread writer;

    public synchronized void connect(String host, int port) throws IOException {
        if (closed.get() || connected.get()) {
            throw new IllegalStateException("Create a new SocketClient for each connection");
        }
        Socket opened = new Socket();
        try {
            opened.connect(new InetSocketAddress(host, port), 5000);
            opened.setTcpNoDelay(true);
            socket = opened;
            connected.set(true);
            reader = new Thread(this::readLoop, "auction-socket-reader");
            writer = new Thread(this::writeLoop, "auction-socket-writer");
            reader.setDaemon(true);
            writer.setDaemon(true);
            reader.start();
            writer.start();
        } catch (IOException ex) {
            opened.close();
            throw ex;
        }
    }

    public CompletableFuture<Void> connectAsync(String host, int port) {
        return CompletableFuture.runAsync(() -> {
            try {
                connect(host, port);
            } catch (IOException ex) {
                throw new CompletionException(ex);
            }
        });
    }

    public boolean isConnected() {
        return connected.get();
    }

    public CompletableFuture<ResponseMessage> request(RequestType type, String token,
                                                       JsonObject payload) {
        return send(new RequestMessage(UUID.randomUUID().toString(), type, token, payload));
    }

    public CompletableFuture<ResponseMessage> send(RequestMessage request) {
        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        if (!connected.get()) {
            future.completeExceptionally(new IOException("Socket is disconnected"));
            return future;
        }
        if (pending.putIfAbsent(request.requestId(), future) != null) {
            future.completeExceptionally(new IllegalArgumentException("Duplicate requestId"));
            return future;
        }
        if (!connected.get()) {
            pending.remove(request.requestId(), future);
            future.completeExceptionally(new IOException("Socket is disconnected"));
            return future;
        }
        if (!outgoing.offer(JsonCodec.toJson(request))) {
            pending.remove(request.requestId(), future);
            future.completeExceptionally(new IOException("Outgoing queue is full"));
        }
        return future;
    }

    public void addEventListener(Consumer<EventMessage> listener) {
        eventListeners.addIfAbsent(listener);
    }

    public void removeEventListener(Consumer<EventMessage> listener) {
        eventListeners.remove(listener);
    }

    public void addDisconnectListener(Consumer<Throwable> listener) {
        disconnectListeners.addIfAbsent(listener);
    }

    public AuctionDto latestAuction(long auctionId) {
        return latestAuctions.get(auctionId);
    }

    private void readLoop() {
        try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
            String line;
            while (connected.get() && (line = LineFraming.readLine(input)) != null) {
                JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
                if (frame.has("requestId")) {
                    ResponseMessage response = JsonCodec.parseResponse(line);
                    rememberPayload(response.payload());
                    CompletableFuture<ResponseMessage> future = pending.remove(response.requestId());
                    if (future != null) {
                        future.complete(response);
                    }
                } else {
                    EventMessage event = JsonCodec.parseEvent(line);
                    JsonObject payload = event.payload();
                    if (payload.has("auction") && remember(payload.get("auction"))) {
                        for (Consumer<EventMessage> listener : eventListeners) {
                            try {
                                listener.accept(event);
                            } catch (RuntimeException ignored) {
                                // One UI listener cannot break the socket reader.
                            }
                        }
                    }
                }
            }
            disconnect(new IOException("Server disconnected"));
        } catch (IOException | RuntimeException ex) {
            disconnect(ex);
        }
    }

    private void writeLoop() {
        try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (connected.get()) {
                String line = outgoing.take();
                output.write(line);
                output.newLine();
                output.flush();
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (connected.get()) {
                disconnect(ex);
            }
        }
    }

    private void rememberPayload(JsonObject payload) {
        if (payload != null) {
            if (payload.has("auction")) {
                remember(payload.get("auction"));
            }
            if (payload.has("auctions")) {
                for (JsonElement auction : payload.getAsJsonArray("auctions")) {
                    remember(auction);
                }
            }
        }
    }

    private boolean remember(JsonElement element) {
        AuctionDto candidate = gson.fromJson(element, AuctionDto.class);
        AtomicBoolean accepted = new AtomicBoolean();
        latestAuctions.compute(candidate.id(), (id, previous) -> {
            if (previous == null || isNewer(candidate, previous)) {
                accepted.set(true);
                return candidate;
            }
            return previous;
        });
        return accepted.get();
    }

    private boolean isNewer(AuctionDto next, AuctionDto current) {
        int nextRank = rank(next.status());
        int currentRank = rank(current.status());
        return nextRank >= currentRank && next.currentPriceVnd() >= current.currentPriceVnd()
                && next.endAtEpochMs() >= current.endAtEpochMs()
                && (nextRank > currentRank || next.currentPriceVnd() > current.currentPriceVnd()
                || next.endAtEpochMs() > current.endAtEpochMs());
    }

    private int rank(AuctionStatus status) {
        return switch (status) {
            case OPEN -> 0;
            case RUNNING -> 1;
            case FINISHED -> 2;
            case PAID, CANCELED -> 3;
        };
    }

    private synchronized void disconnect(Throwable cause) {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // The connection has already failed.
            }
            if (writer != null) {
                writer.interrupt();
            }
            pending.forEach((id, future) -> future.completeExceptionally(cause));
            pending.clear();
            for (Consumer<Throwable> listener : disconnectListeners) {
                try {
                    listener.accept(cause);
                } catch (RuntimeException ignored) {
                    // One UI listener cannot break shutdown.
                }
            }
        }
    }

    @Override
    public void close() {
        disconnect(new IOException("Socket client closed"));
    }
}
