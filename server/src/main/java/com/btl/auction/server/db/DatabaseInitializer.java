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
        try (InputStream input = DatabaseInitializer.class.getResourceAsStream("/schema.sql")) {
            if (input == null) {
                throw new IOException("schema.sql is missing");
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
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
        return database;
    }
}
