package com.btl.auction.server.dao;

import com.btl.auction.common.dto.AutoBidDto;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class AutoBidDao {
    public AutoBidDto find(Connection connection, long auctionId, long bidderId)
            throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "SELECT * FROM auto_bids WHERE auction_id = ? AND bidder_user_id = ?")) {
            sql.setLong(1, auctionId);
            sql.setLong(2, bidderId);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public List<AutoBidDto> list(Connection connection, long auctionId) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "SELECT * FROM auto_bids WHERE auction_id = ? "
                        + "ORDER BY max_bid_vnd DESC, created_at_ms, id")) {
            sql.setLong(1, auctionId);
            try (ResultSet rows = sql.executeQuery()) {
                List<AutoBidDto> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(map(rows));
                }
                return values;
            }
        }
    }

    public AutoBidDto save(Connection connection, long auctionId, long bidderId,
                           long maxBidVnd, long incrementVnd, long now) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "INSERT INTO auto_bids(auction_id, bidder_user_id, max_bid_vnd, "
                        + "increment_vnd, created_at_ms) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(auction_id, bidder_user_id) DO UPDATE SET "
                        + "max_bid_vnd = excluded.max_bid_vnd, "
                        + "increment_vnd = excluded.increment_vnd")) {
            sql.setLong(1, auctionId);
            sql.setLong(2, bidderId);
            sql.setLong(3, maxBidVnd);
            sql.setLong(4, incrementVnd);
            sql.setLong(5, now);
            sql.executeUpdate();
        }
        return find(connection, auctionId, bidderId);
    }

    public void delete(Connection connection, long auctionId, long bidderId) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "DELETE FROM auto_bids WHERE auction_id = ? AND bidder_user_id = ?")) {
            sql.setLong(1, auctionId);
            sql.setLong(2, bidderId);
            sql.executeUpdate();
        }
    }

    private AutoBidDto map(ResultSet row) throws SQLException {
        return new AutoBidDto(row.getLong("id"), row.getLong("auction_id"),
                row.getLong("bidder_user_id"), row.getLong("max_bid_vnd"),
                row.getLong("increment_vnd"), row.getLong("created_at_ms"));
    }
}
