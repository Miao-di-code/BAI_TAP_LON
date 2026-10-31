package com.btl.auction.server.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class DatabaseInitializer {
    private DatabaseInitializer() {
    }

    public static Database initialize(Path path) throws IOException, SQLException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        Database database = new Database(normalized);
        String schema;
        try (InputStream input = DatabaseInitializer.class.getResourceAsStream("/db/schema.sql")) {
            if (input == null) {
                throw new IOException("db/schema.sql is missing");
            }
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
                int version;
                try (java.sql.ResultSet row = statement.executeQuery("PRAGMA user_version")) {
                    version = row.next() ? row.getInt(1) : 0;
                }
                if (version > 2) {
                    throw new SQLException("Database schema version is newer than this application");
                }
                if (version < 2) {
                    String migration;
                    try (InputStream input = DatabaseInitializer.class.getResourceAsStream(
                            "/db/migrations/V2__auto_bids.sql")) {
                        if (input == null) {
                            throw new IOException("V2 migration is missing");
                        }
                        migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    for (String sql : migration.split(";")) {
                        if (!sql.isBlank()) {
                            statement.execute(sql);
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | IOException ex) {
                connection.rollback();
                throw ex;
            }
        }
        return database;
    }
}
