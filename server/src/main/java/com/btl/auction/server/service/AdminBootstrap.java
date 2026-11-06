package com.btl.auction.server.service;

import com.btl.auction.server.db.DatabaseInitializer;
import java.nio.file.Path;
import java.time.Clock;

public final class AdminBootstrap {
    private AdminBootstrap() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: AdminBootstrap <database> <username> <displayName>");
        }
        String password = System.getenv("AUCTION_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Set AUCTION_ADMIN_PASSWORD in the local environment");
        }
        AuthService auth = new AuthService(DatabaseInitializer.initialize(Path.of(args[0])),
                Clock.systemUTC());
        auth.createLocalAdmin(args[1], args[2], password);
        System.out.println("Local Admin created: " + args[1]);
    }
}
