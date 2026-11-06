package com.btl.auction.server;

import com.btl.auction.server.network.SocketServer;
import com.btl.auction.server.service.AntiSnipingPolicy;
import java.nio.file.Path;
import java.time.Clock;

public final class ServerMain {
    private static final int DEFAULT_PORT = 5555;

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path database = args.length > 1 ? Path.of(args[1]) : Path.of("data", "auction.db");
        long windowSeconds = Long.getLong("auction.snipe.window.seconds", 30L);
        long extensionSeconds = Long.getLong("auction.snipe.extension.seconds", 30L);
        AntiSnipingPolicy policy = new AntiSnipingPolicy(
                Math.multiplyExact(windowSeconds, 1000),
                Math.multiplyExact(extensionSeconds, 1000));
        try (SocketServer server = new SocketServer(port, database, Clock.systemUTC(), policy)) {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "auction-shutdown"));
            System.out.println("Auction server listening on 127.0.0.1:" + server.port());
            server.awaitTermination();
        }
    }
}
