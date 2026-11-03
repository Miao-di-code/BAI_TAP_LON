package com.btl.auction.server.dao;

import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.model.AuctionStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class AuctionDao {
    public AuctionDto find(Connection connection, long id) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("SELECT * FROM auctions WHERE id = ?")) {
            sql.setLong(1, id);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public boolean existsForItem(Connection connection, long itemId) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "SELECT 1 FROM auctions WHERE item_id = ?")) {
            sql.setLong(1, itemId);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next();
            }
        }
    }

    public List<AuctionDto> list(Connection connection, AuctionStatus status) throws SQLException {
        String query = "SELECT * FROM auctions" + (status == null ? "" : " WHERE status = ?")
                + " ORDER BY id";
        try (PreparedStatement sql = connection.prepareStatement(query)) {
            if (status != null) {
                sql.setString(1, status.name());
            }
            try (ResultSet rows = sql.executeQuery()) {
                List<AuctionDto> auctions = new ArrayList<>();
                while (rows.next()) {
                    auctions.add(map(rows));
                }
                return auctions;
            }
        }
    }

    public AuctionDto insert(Connection connection, long itemId, long start, long end, long price,
                             AuctionStatus status) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "INSERT INTO auctions(item_id, start_at_ms, end_at_ms, current_price_vnd, status) "
                        + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setLong(1, itemId);
            sql.setLong(2, start);
            sql.setLong(3, end);
            sql.setLong(4, price);
            sql.setString(5, status.name());
            sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) {
                keys.next();
                return new AuctionDto(keys.getLong(1), itemId, start, end, price, null, status);
            }
        }
    }

    public boolean changeStatus(Connection connection, long id, AuctionStatus from,
                                AuctionStatus to) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "UPDATE auctions SET status = ? WHERE id = ? AND status = ?")) {
            sql.setString(1, to.name());
            sql.setLong(2, id);
            sql.setString(3, from.name());
            return sql.executeUpdate() == 1;
        }
    }

    public boolean updatePrice(Connection connection, long id, long oldPrice, long newPrice,
                               long bidderId) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "UPDATE auctions SET current_price_vnd = ?, leader_user_id = ? "
                        + "WHERE id = ? AND current_price_vnd = ? AND status = 'RUNNING'")) {
            sql.setLong(1, newPrice);
            sql.setLong(2, bidderId);
            sql.setLong(3, id);
            sql.setLong(4, oldPrice);
            return sql.executeUpdate() == 1;
        }
    }

    public boolean extendEnd(Connection connection, long id, long oldEnd, long newEnd)
            throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "UPDATE auctions SET end_at_ms = ? WHERE id = ? AND end_at_ms = ? "
                        + "AND status = 'RUNNING'")) {
            sql.setLong(1, newEnd);
            sql.setLong(2, id);
            sql.setLong(3, oldEnd);
            return sql.executeUpdate() == 1;
        }
    }

    private AuctionDto map(ResultSet rows) throws SQLException {
        long leader = rows.getLong("leader_user_id");
        Long leaderId = rows.wasNull() ? null : leader;
        return new AuctionDto(rows.getLong("id"), rows.getLong("item_id"),
                rows.getLong("start_at_ms"), rows.getLong("end_at_ms"),
                rows.getLong("current_price_vnd"), leaderId,
                AuctionStatus.valueOf(rows.getString("status")));
    }
}
