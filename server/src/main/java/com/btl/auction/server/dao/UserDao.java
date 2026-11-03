package com.btl.auction.server.dao;

import com.btl.auction.common.dto.UserDto;
import com.btl.auction.common.model.UserRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class UserDao {
    public record StoredUser(UserDto user, String passwordHash) {
    }

    public StoredUser findByUsername(Connection connection, String username) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "SELECT id, username, display_name, password_hash, role FROM users WHERE username = ?")) {
            sql.setString(1, username);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public StoredUser findById(Connection connection, long id) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "SELECT id, username, display_name, password_hash, role FROM users WHERE id = ?")) {
            sql.setLong(1, id);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public UserDto insert(Connection connection, String username, String displayName,
                          String hash, UserRole role, long now) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "INSERT INTO users(username, display_name, password_hash, role, created_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setString(1, username);
            sql.setString(2, displayName);
            sql.setString(3, hash);
            sql.setString(4, role.name());
            sql.setLong(5, now);
            sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) {
                keys.next();
                return new UserDto(keys.getLong(1), username, displayName, role);
            }
        }
    }

    private StoredUser map(ResultSet rows) throws SQLException {
        return new StoredUser(new UserDto(rows.getLong("id"), rows.getString("username"),
                rows.getString("display_name"), UserRole.valueOf(rows.getString("role"))),
                rows.getString("password_hash"));
    }
}
