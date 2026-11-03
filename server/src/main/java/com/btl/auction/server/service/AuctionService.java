package com.btl.auction.server.service;

import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.AutoBidDto;
import com.btl.auction.common.dto.BidDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.model.AuctionStatus;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.common.protocol.EventType;
import com.btl.auction.server.dao.AuctionDao;
import com.btl.auction.server.dao.AutoBidDao;
import com.btl.auction.server.dao.BidDao;
import com.btl.auction.server.dao.ItemDao;
import com.btl.auction.server.dao.UserDao;
import com.btl.auction.server.db.Database;
import com.btl.auction.server.db.Transactions;
import com.btl.auction.server.event.AuctionEventPublisher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Callable;

public final class AuctionService {
    public record BidResult(BidDto bid, AuctionDto auction) {
    }

    public record AutoBidResult(AutoBidDto autoBid, AuctionDto auction) {
    }

    private record BidCommit(BidDto bid, AuctionDto auction, List<EventMessage> events) {
    }

    private record AutoBidCommit(AutoBidDto autoBid, AuctionDto auction,
                                 List<EventMessage> events) {
    }

    private final Database database;
    private final Clock clock;
    private final AuctionEventPublisher publisher;
    private final AntiSnipingPolicy antiSniping;
    private final AuctionManager manager = AuctionManager.getInstance();
    private final AuctionDao auctions = new AuctionDao();
    private final BidDao bids = new BidDao();
    private final AutoBidDao autoBids = new AutoBidDao();
    private final ItemDao items = new ItemDao();
    private final UserDao users = new UserDao();
    private final Gson gson = new Gson();

    public AuctionService(Database database, Clock clock, AuctionEventPublisher publisher) {
        this(database, clock, publisher, AntiSnipingPolicy.disabled());
    }

    public AuctionService(Database database, Clock clock, AuctionEventPublisher publisher,
                          AntiSnipingPolicy antiSniping) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.antiSniping = Objects.requireNonNull(antiSniping, "antiSniping");
    }

    public AuctionDto createAuction(long sellerId, long itemId, long start, long end) {
        long now = clock.millis();
        if (start < now || end <= start) {
            throw new ServiceException(ErrorCode.VALIDATION_ERROR, "Invalid auction time range");
        }
        AuctionDto auction = Transactions.write(database, connection -> {
            ItemDto item = items.find(connection, itemId);
            UserDao.StoredUser seller = users.findById(connection, sellerId);
            if (seller == null || seller.user().role() != UserRole.SELLER
                    || item == null || item.sellerId() != sellerId) {
                throw new ServiceException(ErrorCode.FORBIDDEN, "Seller must own item");
            }
            if (auctions.existsForItem(connection, itemId)) {
                throw new ServiceException(ErrorCode.CONFLICT, "Item already has an auction");
            }
            try {
                return auctions.insert(connection, itemId, start, end, item.startingPriceVnd(),
                        start == now ? AuctionStatus.RUNNING : AuctionStatus.OPEN);
            } catch (SQLException ex) {
                if (ex.getMessage().contains("UNIQUE")) {
                    throw new ServiceException(ErrorCode.CONFLICT, "Item already has an auction", ex);
                }
                throw ex;
            }
        });
        if (auction.status() == AuctionStatus.RUNNING) {
            publish(EventType.AUCTION_STARTED, auction, null);
        }
        return auction;
    }

    public AuctionDto getAuction(long id) {
        refreshOne(id, clock.millis());
        return Transactions.read(database, connection -> requireAuction(connection, id));
    }

    public List<AuctionDto> listAuctions(AuctionStatus status) {
        closeExpired(clock.millis());
        return Transactions.read(database, connection -> auctions.list(connection, status));
    }

    public List<BidDto> getBids(long auctionId) {
        return Transactions.read(database, connection -> {
            requireAuction(connection, auctionId);
            return bids.list(connection, auctionId);
        });
    }

    public BidResult placeBid(long bidderId, long auctionId, long amountVnd) {
        refreshOne(auctionId, clock.millis());
        BidResult result = locked(auctionId, () -> {
            BidCommit committed = Transactions.write(database, connection -> {
                requireBidder(connection, bidderId);
                AuctionDto auction = requireAuction(connection, auctionId);
                long now = clock.millis();
                requireRunning(auction, now);
                if (amountVnd <= auction.currentPriceVnd()) {
                    throw new ServiceException(ErrorCode.BID_TOO_LOW, "Bid must exceed current price");
                }
                List<EventMessage> emitted = new ArrayList<>();
                BidDto bid = recordBid(connection, auction, bidderId, amountVnd, now, emitted);
                AuctionDto updated = requireAuction(connection, auctionId);
                updated = resolveAutoBid(connection, updated, now, emitted);
                updated = extendIfNeeded(connection, updated, now, emitted);
                return new BidCommit(bid, updated, emitted);
            });
            committed.events().forEach(publisher::enqueue);
            return new BidResult(committed.bid(), committed.auction());
        });
        publisher.drain(auctionId);
        return result;
    }

    public AutoBidResult setAutoBid(long bidderId, long auctionId, long maxBidVnd,
                                    long incrementVnd) {
        refreshOne(auctionId, clock.millis());
        AutoBidResult result = locked(auctionId, () -> {
            AutoBidCommit committed = Transactions.write(database, connection -> {
                requireBidder(connection, bidderId);
                AuctionDto auction = requireAuction(connection, auctionId);
                long now = clock.millis();
                requireRunning(auction, now);
                if (maxBidVnd <= auction.currentPriceVnd() || incrementVnd <= 0) {
                    throw new ServiceException(ErrorCode.VALIDATION_ERROR,
                            "Max bid must exceed current price and increment must be positive");
                }
                AutoBidDto config = autoBids.save(connection, auctionId, bidderId,
                        maxBidVnd, incrementVnd, now);
                List<EventMessage> emitted = new ArrayList<>();
                AuctionDto updated = resolveAutoBid(connection, auction, now, emitted);
                if (!emitted.isEmpty()) {
                    updated = extendIfNeeded(connection, updated, now, emitted);
                }
                return new AutoBidCommit(config, updated, emitted);
            });
            committed.events().forEach(publisher::enqueue);
            return new AutoBidResult(committed.autoBid(), committed.auction());
        });
        publisher.drain(auctionId);
        return result;
    }

    public AutoBidDto getAutoBid(long bidderId, long auctionId) {
        return Transactions.read(database, connection -> {
            requireBidder(connection, bidderId);
            requireAuction(connection, auctionId);
            return autoBids.find(connection, auctionId, bidderId);
        });
    }

    public void removeAutoBid(long bidderId, long auctionId) {
        locked(auctionId, () -> Transactions.write(database, connection -> {
            requireBidder(connection, bidderId);
            requireAuction(connection, auctionId);
            autoBids.delete(connection, auctionId, bidderId);
            return null;
        }));
    }

    private BidDto recordBid(Connection connection, AuctionDto auction, long bidderId,
                             long amount, long now, List<EventMessage> emitted) throws SQLException {
        BidDto bid = bids.insert(connection, auction.id(), bidderId, amount, now);
        if (!auctions.updatePrice(connection, auction.id(), auction.currentPriceVnd(),
                amount, bidderId)) {
            throw new ServiceException(ErrorCode.CONFLICT, "Auction price changed");
        }
        emitted.add(event(EventType.BID_UPDATED, requireAuction(connection, auction.id()), bid));
        return bid;
    }

    private AuctionDto resolveAutoBid(Connection connection, AuctionDto auction, long now,
                                      List<EventMessage> emitted) throws SQLException {
        AutoBidCalculator.Decision decision = AutoBidCalculator.next(
                autoBids.list(connection, auction.id()), auction.currentPriceVnd(),
                auction.leaderUserId());
        if (decision != null) {
            recordBid(connection, auction, decision.bidder().bidderUserId(),
                    decision.amountVnd(), now, emitted);
            return requireAuction(connection, auction.id());
        }
        return auction;
    }

    private AuctionDto extendIfNeeded(Connection connection, AuctionDto auction, long now,
                                      List<EventMessage> emitted) throws SQLException {
        long newEnd = antiSniping.endAfterAcceptedBid(now, auction.endAtEpochMs());
        if (newEnd == auction.endAtEpochMs()) {
            return auction;
        }
        if (!auctions.extendEnd(connection, auction.id(), auction.endAtEpochMs(), newEnd)) {
            throw new ServiceException(ErrorCode.CONFLICT, "Auction end time changed");
        }
        AuctionDto extended = requireAuction(connection, auction.id());
        emitted.add(event(EventType.AUCTION_EXTENDED, extended, null));
        return extended;
    }

    private void requireBidder(Connection connection, long bidderId) throws SQLException {
        UserDao.StoredUser bidder = users.findById(connection, bidderId);
        if (bidder == null || bidder.user().role() != UserRole.BIDDER) {
            throw new ServiceException(ErrorCode.FORBIDDEN, "Bidder role required");
        }
    }

    private void requireRunning(AuctionDto auction, long now) {
        if (auction.status() != AuctionStatus.RUNNING
                || now < auction.startAtEpochMs() || now >= auction.endAtEpochMs()) {
            throw new ServiceException(ErrorCode.AUCTION_NOT_RUNNING, "Auction is not running");
        }
    }

    public int closeExpired(long now) {
        List<AuctionDto> list = Transactions.read(database, connection -> auctions.list(connection, null));
        int changes = 0;
        for (AuctionDto auction : list) {
            if ((auction.status() == AuctionStatus.OPEN && now >= auction.startAtEpochMs())
                    || (auction.status() == AuctionStatus.RUNNING && now >= auction.endAtEpochMs())) {
                changes += refreshOne(auction.id(), now);
            }
        }
        return changes;
    }

    public AuctionDto markPaid(long actorId, long auctionId) {
        refreshOne(auctionId, clock.millis());
        return finalizeAuction(actorId, auctionId, AuctionStatus.PAID);
    }

    public AuctionDto cancel(long actorId, long auctionId) {
        refreshOne(auctionId, clock.millis());
        return finalizeAuction(actorId, auctionId, AuctionStatus.CANCELED);
    }

    private AuctionDto finalizeAuction(long actorId, long auctionId, AuctionStatus target) {
        return locked(auctionId, () -> Transactions.write(database, connection -> {
            AuctionDto auction = requireAuction(connection, auctionId);
            ItemDto item = items.find(connection, auction.itemId());
            UserDao.StoredUser actor = users.findById(connection, actorId);
            if (actor == null || !(actor.user().role() == UserRole.ADMIN
                    || (actor.user().role() == UserRole.SELLER && item.sellerId() == actorId))) {
                throw new ServiceException(ErrorCode.FORBIDDEN, "Auction management forbidden");
            }
            if (auction.status() != AuctionStatus.FINISHED
                    || (target == AuctionStatus.PAID && auction.leaderUserId() == null)) {
                throw new ServiceException(ErrorCode.CONFLICT, "Invalid auction completion");
            }
            if (!auctions.changeStatus(connection, auctionId, AuctionStatus.FINISHED, target)) {
                throw new ServiceException(ErrorCode.CONFLICT, "Auction status changed");
            }
            return requireAuction(connection, auctionId);
        }));
    }

    private int refreshOne(long id, long now) {
        List<AuctionDto> changed = locked(id, () -> {
            List<AuctionDto> committed = Transactions.write(database, connection -> {
                AuctionDto auction = requireAuction(connection, id);
                java.util.ArrayList<AuctionDto> result = new java.util.ArrayList<>();
                if (auction.status() == AuctionStatus.OPEN && now >= auction.startAtEpochMs()) {
                    if (auctions.changeStatus(connection, id, AuctionStatus.OPEN, AuctionStatus.RUNNING)) {
                        auction = requireAuction(connection, id);
                        result.add(auction);
                    }
                }
                if (auction.status() == AuctionStatus.RUNNING && now >= auction.endAtEpochMs()) {
                    if (auctions.changeStatus(connection, id, AuctionStatus.RUNNING,
                            AuctionStatus.FINISHED)) {
                        result.add(requireAuction(connection, id));
                    }
                }
                return result;
            });
            for (AuctionDto auction : committed) {
                publisher.enqueue(event(auction.status() == AuctionStatus.RUNNING
                        ? EventType.AUCTION_STARTED : EventType.AUCTION_FINISHED, auction, null));
            }
            return committed;
        });
        publisher.drain(id);
        return changed.size();
    }

    private AuctionDto requireAuction(Connection connection, long id) throws SQLException {
        AuctionDto auction = auctions.find(connection, id);
        if (auction == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND, "Auction not found");
        }
        return auction;
    }

    private <T> T locked(long auctionId, Callable<T> action) {
        try {
            return manager.withAuctionLock(auctionId, action);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR, "Auction operation failed", ex);
        }
    }

    private void publish(EventType type, AuctionDto auction, BidDto bid) {
        publisher.enqueue(event(type, auction, bid));
        publisher.drain(auction.id());
    }

    private EventMessage event(EventType type, AuctionDto auction, BidDto bid) {
        JsonObject payload = new JsonObject();
        payload.add("auction", gson.toJsonTree(auction));
        if (bid != null) {
            payload.add("bid", gson.toJsonTree(bid));
        }
        return new EventMessage(type, auction.id(), payload);
    }
}
