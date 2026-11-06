package com.btl.auction.server.service;

import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.server.db.Database;
import com.btl.auction.server.db.DatabaseInitializer;
import com.btl.auction.server.db.Transactions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SnapshotService {
    private static final String[] TABLES_V1 = {"users", "items", "auctions", "bids"};
    private static final String[] TABLES = {"users", "items", "auctions", "bids", "auto_bids"};
    private final Database database;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public SnapshotService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void exportSnapshot(Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (output.toAbsolutePath().normalize().equals(database.path())) {
            throw new IOException("Snapshot output must differ from the active database");
        }
        JsonObject snapshot = Transactions.read(database, connection -> {
            connection.setAutoCommit(false);
            try {
                JsonObject result = new JsonObject();
                result.addProperty("version", 2);
                for (String table : TABLES) {
                    result.add(table, readTable(connection, table));
                }
                connection.commit();
                return result;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        });
        Files.writeString(output, gson.toJson(snapshot), StandardCharsets.UTF_8);
    }

    public static Database restoreToNewDatabase(Path snapshotPath, Path destination)
            throws IOException, SQLException {
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        if (Files.exists(target)) {
            throw new IOException("Restore destination already exists");
        }
        JsonObject snapshot;
        String[] tables;
        try {
            snapshot = JsonParser.parseString(Files.readString(snapshotPath, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int version = snapshot.get("version").getAsInt();
            if (version != 1 && version != 2) {
                throw new IllegalArgumentException("Unsupported snapshot version");
            }
            tables = version == 1 ? TABLES_V1 : TABLES;
            for (String table : tables) {
                if (snapshot.getAsJsonArray(table) == null) {
                    throw new IllegalArgumentException("Missing snapshot table: " + table);
                }
            }
        } catch (RuntimeException ex) {
            throw new IOException("Invalid snapshot", ex);
        }
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(target.getParent(), "auction-restore-", ".db");
        try {
            Database restored = DatabaseInitializer.initialize(staging);
            Transactions.write(restored, connection -> {
                for (String table : tables) {
                    insertRows(connection, table, snapshot.getAsJsonArray(table));
                }
                try (Statement sql = connection.createStatement();
                     ResultSet errors = sql.executeQuery("PRAGMA foreign_key_check")) {
                    if (errors.next()) {
                        throw new ServiceException(ErrorCode.VALIDATION_ERROR,
                                "Snapshot has invalid references");
                    }
                }
                return null;
            });
            Files.move(staging, target);
            return new Database(target);
        } catch (IOException | SQLException | RuntimeException ex) {
            Files.deleteIfExists(staging);
            throw ex;
        }
    }

    private static JsonArray readTable(Connection connection, String table) throws SQLException {
        JsonArray rows = new JsonArray();
        try (Statement sql = connection.createStatement();
             ResultSet result = sql.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next()) {
                JsonObject row = new JsonObject();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String name = meta.getColumnName(i);
                    Object value = result.getObject(i);
                    if (value == null) {
                        row.add(name, null);
                    } else if (value instanceof Number number) {
                        row.addProperty(name, number);
                    } else {
                        row.addProperty(name, value.toString());
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void insertRows(Connection connection, String table, JsonArray rows)
            throws SQLException {
        String[] columns = switch (table) {
            case "users" -> new String[] {"id", "username", "display_name", "password_hash",
                    "role", "created_at_ms"};
            case "items" -> new String[] {"id", "seller_id", "category", "name", "description",
                    "starting_price_vnd", "detail_json"};
            case "auctions" -> new String[] {"id", "item_id", "start_at_ms", "end_at_ms",
                    "current_price_vnd", "leader_user_id", "status"};
            case "bids" -> new String[] {"id", "auction_id", "bidder_user_id", "amount_vnd",
                    "created_at_ms"};
            case "auto_bids" -> new String[] {"id", "auction_id", "bidder_user_id",
                    "max_bid_vnd", "increment_vnd", "created_at_ms"};
            default -> throw new IllegalArgumentException("Unknown table");
        };
        String query = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES ("
                + "?,".repeat(columns.length - 1) + "?)";
        try (PreparedStatement sql = connection.prepareStatement(query)) {
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                for (int i = 0; i < columns.length; i++) {
                    JsonElement value = row.get(columns[i]);
                    if (value == null) {
                        throw new ServiceException(ErrorCode.VALIDATION_ERROR,
                                "Snapshot is missing " + columns[i]);
                    }
                    if (value.isJsonNull()) {
                        sql.setObject(i + 1, null);
                    } else if (isNumeric(columns[i])) {
                        sql.setLong(i + 1, value.getAsLong());
                    } else {
                        sql.setString(i + 1, value.getAsString());
                    }
                }
                sql.executeUpdate();
            }
        }
    }

    private static boolean isNumeric(String column) {
        return column.equals("id") || column.endsWith("_id") || column.endsWith("_ms")
                || column.endsWith("_vnd");
    }
}
