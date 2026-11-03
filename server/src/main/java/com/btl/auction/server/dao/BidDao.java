package com.btl.auction.server.dao;

import com.btl.auction.common.dto.BidDto;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class BidDao {
    public BidDto insert(Connection connection, long auctionId, long bidderId,
                         long amount, long now) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "INSERT INTO bids(auction_id, bidder_user_id, amount_vnd, created_at_ms) "
                        + "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setLong(1, auctionId);
            sql.setLong(2, bidderId);
            sql.setLong(3, amount);
            sql.setLong(4, now);
            sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) {
                keys.next();
                return new BidDto(keys.getLong(1), auctionId, bidderId, amount, now);
            }
        }
    }

    public List<BidDto> list(Connection connection, long auctionId) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(
                "SELECT * FROM bids WHERE auction_id = ? ORDER BY created_at_ms, id")) {
            sql.setLong(1, auctionId);
            try (ResultSet rows = sql.executeQuery()) {
                List<BidDto> bids = new ArrayList<>();
                while (rows.next()) {
                    bids.add(new BidDto(rows.getLong("id"), rows.getLong("auction_id"),
                            rows.getLong("bidder_user_id"), rows.getLong("amount_vnd"),
                            rows.getLong("created_at_ms")));
                }
                return bids;
            }
        }
    }
}
