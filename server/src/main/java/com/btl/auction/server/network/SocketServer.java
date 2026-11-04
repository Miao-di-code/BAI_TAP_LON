package com.btl.auction.server.network;

import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.InvalidProtocolException;
import com.btl.auction.common.protocol.JsonCodec;
import com.btl.auction.common.protocol.LineFraming;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.ResponseMessage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/** Sequential TCP server; concurrent client handling is added in week 3. */
public final class SocketServer implements AutoCloseable {
    private final ServerSocket listener;
    private final RequestDispatcher dispatcher;

    public SocketServer(int port) throws IOException {
        this(port, new RequestDispatcher());
    }

    public SocketServer(int port, RequestDispatcher dispatcher) throws IOException {
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        listener = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
    }

    public int port() {
        return listener.getLocalPort();
    }

    public void serve() throws IOException {
        while (!listener.isClosed()) {
            Socket client;
            try {
                client = listener.accept();
            } catch (SocketException ex) {
                if (!listener.isClosed()) {
                    throw ex;
                }
                break;
            }
            try (client) {
                respond(client);
            } catch (IOException ignored) {
                // A client disconnect must not stop the listening socket.
            }
        }
    }

    private void respond(Socket client) throws IOException {
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                client.getOutputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = LineFraming.readLine(client.getInputStream())) != null) {
            ResponseMessage response;
            try {
                RequestMessage request = JsonCodec.parseRequest(line);
                response = dispatcher.dispatch(request);
            } catch (InvalidProtocolException ex) {
                response = ResponseMessage.error(null, ErrorCode.INVALID_REQUEST,
                        "Invalid request JSON");
            }
            output.write(JsonCodec.toJson(response));
            output.newLine();
            output.flush();
        }
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // The socket is already closed.
        }
    }
}
