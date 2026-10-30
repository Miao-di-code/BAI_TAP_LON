package com.btl.auction.server;

import com.btl.auction.server.network.SocketServer;

public final class ServerMain {
    private static final int DEFAULT_PORT = 5555;

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        try (SocketServer server = new SocketServer(port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            System.out.println("Auction server listening on 127.0.0.1:" + server.port());
            server.serve();
        }
    }
}
