package com.btl.auction.server.dao;

import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.model.ItemCategory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class ItemDao {
    public ItemDto find(Connection connection, long id) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("SELECT * FROM items WHERE id = ?")) {
            sql.setLong(1, id);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public List<ItemDto> list(Connection connection, Long sellerId) throws SQLException {
        String query = "SELECT * FROM items" + (sellerId == null ? "" : " WHERE seller_id = ?")
                + " ORDER BY id";
        try (PreparedStatement sql = connection.prepareStatement(query)) {
            if (sellerId != null) {
                sql.setLong(1, sellerId);
            }
            try (ResultSet rows = sql.executeQuery()) {
                List<ItemDto> items = new ArrayList<>();
                while (rows.next()) {
                    items.add(map(rows));
                }
                return items;
            }
        }
    }

    public ItemDto insert(Connection connection, long sellerId, ItemCategory category, String name,
                          String description, long price, String detail) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "INSERT INTO items(seller_id, category, name, description, starting_price_vnd, detail_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setLong(1, sellerId);
            sql.setString(2, category.name());
            sql.setString(3, name);
            sql.setString(4, description);
            sql.setLong(5, price);
            sql.setString(6, detail);
            sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) {
                keys.next();
                return new ItemDto(keys.getLong(1), sellerId, category, name, description, price, detail);
            }
        }
    }

    public void update(Connection connection, ItemDto item) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "UPDATE items SET name = ?, description = ?, starting_price_vnd = ?, detail_json = ? "
                        + "WHERE id = ?")) {
            sql.setString(1, item.name());
            sql.setString(2, item.description());
            sql.setLong(3, item.startingPriceVnd());
            sql.setString(4, item.detail());
            sql.setLong(5, item.id());
            sql.executeUpdate();
        }
    }

    public void delete(Connection connection, long id) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("DELETE FROM items WHERE id = ?")) {
            sql.setLong(1, id);
            sql.executeUpdate();
        }
    }

    private ItemDto map(ResultSet rows) throws SQLException {
        return new ItemDto(rows.getLong("id"), rows.getLong("seller_id"),
                ItemCategory.valueOf(rows.getString("category")), rows.getString("name"),
                rows.getString("description"), rows.getLong("starting_price_vnd"),
                rows.getString("detail_json"));
    }
}
