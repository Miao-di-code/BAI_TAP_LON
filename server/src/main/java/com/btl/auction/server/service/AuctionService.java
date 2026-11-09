package com.btl.auction.server.service;

import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.BidDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.model.AuctionStatus;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.common.protocol.EventType;
import com.btl.auction.server.dao.AuctionDao;
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

public final class AuctionService {//Bộ não nghiệp vụ đấu giá.
    public record BidResult(BidDto bid, AuctionDto auction) { //Gói kết quả trả về cho network sau khi đặt giá thành công
    }

    private record BidCommit(BidDto bid, AuctionDto auction, List<EventMessage> events) { //Gói dữ liệu dùng nội bộ(placeBid)
    }

    private final Database database;        //Tính đóng gói
    private final Clock clock;
    private final AuctionEventPublisher publisher;
    private final AuctionManager manager = AuctionManager.getInstance();
    private final AuctionDao auctions = new AuctionDao();
    private final BidDao bids = new BidDao();
    private final ItemDao items = new ItemDao();
    private final UserDao users = new UserDao();
    private final Gson gson = new Gson();

    public AuctionService(Database database, Clock clock, AuctionEventPublisher publisher) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    //Tạo phiên đấu giá mới.
    public AuctionDto createAuction(long sellerId, long itemId, long start, long end) {
        long now = clock.millis();
        if (start < now || end <= start) {
            throw new ServiceException(ErrorCode.VALIDATION_ERROR, "Invalid auction time range");
        }
        AuctionDto auction = Transactions.write(database, connection -> {
            ItemDto item = items.find(connection, itemId);
            UserDao.StoredUser seller = users.findById(connection, sellerId);
            if (seller == null || seller.user().role() != UserRole.SELLER
                    || item == null || item.sellerId() != sellerId) { //Ktra seller, item có khớp không hoặc có đúng vai trò không.
                throw new ServiceException(ErrorCode.FORBIDDEN, "Seller must own item");
            }
            if (auctions.existsForItem(connection, itemId)) { //Kiểm tra món hàng này đã từng lên sàn chưa.
                throw new ServiceException(ErrorCode.CONFLICT, "Item already has an auction");
            }
            try {
                return auctions.insert(connection, itemId, start, end, item.startingPriceVnd(),
                        start == now ? AuctionStatus.RUNNING : AuctionStatus.OPEN);
            } catch (SQLException ex) { //Phòng trường hợp 2 luồng cùng insert 1 lúc
                if (ex.getMessage().contains("UNIQUE")) {
                    throw new ServiceException(ErrorCode.CONFLICT, "Item already has an auction", ex);
                }
                throw ex;
            }
        });
        if (auction.status() == AuctionStatus.RUNNING) {
            publish(EventType.AUCTION_STARTED, auction, null);//Nếu trạng thái RUNNING thì phát loa event
        }
        return auction;
    }


    //Các hàm truy vấn dữ liệu
    public AuctionDto getAuction(long id) {
        refreshOne(id, clock.millis()); //Cập nhật trước
        return Transactions.read(database, connection -> requireAuction(connection, id));//mở kết nối chỉ đọc v gọi requireAuction để lấy AuctionDto ra.
    }

    public List<AuctionDto> listAuctions(AuctionStatus status) {
        closeExpired(clock.millis()); //Quét và cập nhật tất cả các phiên đã đến giờ mở hoặc hết hạn.
        return Transactions.read(database, connection -> auctions.list(connection, status));
    }

    public List<BidDto> getBids(long auctionId) {
        return Transactions.read(database, connection -> {
            requireAuction(connection, auctionId);
            return bids.list(connection, auctionId);
        }); //Kiểm tra các phiên tồn tại bằng requireAuction nếu có trả về danh sách lịch sử đặt giá(bid.list(...)).
    }


    //Đặt giá đa luồng.
    public BidResult placeBid(long bidderId, long auctionId, long amountVnd) {
        refreshOne(auctionId, clock.millis());
        BidResult result = locked(auctionId, () -> { //truyền một Callable vào hàm khóa -> chỉ khi lấy được ReentrantLock của auctionID đó thì code trong mới được chạy.
            BidCommit committed = Transactions.write(database, connection -> {
                requireBidder(connection, bidderId); //Kiểm tra người đặt cso phải Bidder
                AuctionDto auction = requireAuction(connection, auctionId); //Lấy thông tin phiên mới nhất
                long now = clock.millis();
                requireRunning(auction, now);//Ktra RUNNING.
                if (amountVnd <= auction.currentPriceVnd()) {//Ktra giá có hợp lệ.
                    throw new ServiceException(ErrorCode.BID_TOO_LOW, "Bid must exceed current price");
                }
                List<EventMessage> emitted = new ArrayList<>(); //Tạo danh sách sự kiện tạm.
                BidDto bid = recordBid(connection, auction, bidderId, amountVnd, now, emitted); //Ghi db bằng hàm phụ recordBid.
                AuctionDto updated = requireAuction(connection, auctionId); //Đọc lại phiên cập nhật.
                return new BidCommit(bid, updated, emitted); //Gói cả 3 thứ trên lại.
            });
            committed.events().forEach(publisher::enqueue);//cách viết tắt của Lambda(event -> publisher.enqueue(event)).
            //Ý nghĩa:duyệt qua từng sự kiện trong committed.events() và nhét từng cái vào hàng đợi của publisher.
            return new BidResult(committed.bid(), committed.auction());
        });
        publisher.drain(auctionId);//Sau khi ra khỏi locked(), mới gọi drain để xả hàng đợi phát tin.
        return result;
    }

    private BidDto recordBid(Connection connection, AuctionDto auction, long bidderId,
                             long amount, long now, List<EventMessage> emitted) throws SQLException {
        BidDto bid = bids.insert(connection, auction.id(), bidderId, amount, now); //thêm bản ghi mới nhất vào bids.
        if (!auctions.updatePrice(connection, auction.id(), auction.currentPriceVnd(),
                amount, bidderId)) {
            throw new ServiceException(ErrorCode.CONFLICT, "Auction price changed");
        } //Gọi auction.updatePrice() để cập nhật giá mới kèm đk giá cũ phải khớp.Nếu trong lúc đó giá cũ đã bị thay đổi thì ném lỗi CONFLICT để Rollback toàn bộ.
        emitted.add(event(EventType.BID_UPDATED, requireAuction(connection, auction.id()), bid));//Tạo bản tin EventType.BID_UPDATE nhet vào danh sách emitted.
        return bid;
    }


    //Các hàm kiểm tra điều kiện
    private void requireBidder(Connection connection, long bidderId) throws SQLException {
        UserDao.StoredUser bidder = users.findById(connection, bidderId);
        if (bidder == null || bidder.user().role() != UserRole.BIDDER) {
            throw new ServiceException(ErrorCode.FORBIDDEN, "Bidder role required");
        } //Tìm User trong db. Nếu NULL hoặc khác Bidder thì chặn.
    }

    private void requireRunning(AuctionDto auction, long now) {
        if (auction.status() != AuctionStatus.RUNNING
                || now < auction.startAtEpochMs() || now >= auction.endAtEpochMs()) {
            throw new ServiceException(ErrorCode.AUCTION_NOT_RUNNING, "Auction is not running");
        }//Ktra trạng thái phiên đấu giá.
    }

    private AuctionDto requireAuction(Connection connection, long id) throws SQLException {
        AuctionDto auction = auctions.find(connection, id); //Tìm phiên đấu giá theo id
        if (auction == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND, "Auction not found");
        } //Nếu ko tìm thấy -> ném lỗi.
        return auction;
    }


    //Các hàm quản lý vòng đời của phiên
    public int closeExpired(long now) {
        List<AuctionDto> list = Transactions.read(database, connection -> auctions.list(connection, null)); //Đọc toàn bộ danh sách các phiên đấu giá từ db
        int changes = 0;
        for (AuctionDto auction : list) {
            if ((auction.status() == AuctionStatus.OPEN && now >= auction.startAtEpochMs())
                    || (auction.status() == AuctionStatus.RUNNING && now >= auction.endAtEpochMs())) {
                changes += refreshOne(auction.id(), now);
            }
        }
        return changes; //Vòng lặp for qua danh sách thấy phiên nào chưa thay trạng thái dù đã đến lúc thì gọi refreshOne để chuyển trạng thái và cộng dồn số phiên vào biến changes.
    }

    public AuctionDto markPaid(long actorId, long auctionId) {
        refreshOne(auctionId, clock.millis()); //đồng bộ trước
        return finalizeAuction(actorId, auctionId, AuctionStatus.PAID);
    }
    //2 hàm này đều dùng để khi người bán hoặc admin xác nhận thanh toán hoặc hủy thì gọi finalizeAuction với trạng thái đích đến tương ứng(PAID hoặc CANCELED)
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
            } //Ktra người thực hiện là admin hoặc ch của món hàng đó(Seller).
            if (auction.status() != AuctionStatus.FINISHED
                    || (target == AuctionStatus.PAID && auction.leaderUserId() == null)) {
                throw new ServiceException(ErrorCode.CONFLICT, "Invalid auction completion");
            }//Ktra trạng thái phiên có FINISHED chưa và ktra phiên đó có người thắng không(leaderUserId())
            if (!auctions.changeStatus(connection, auctionId, AuctionStatus.FINISHED, target)) {
                throw new ServiceException(ErrorCode.CONFLICT, "Auction status changed");
            }//Gọi lệnh SQL đổi trạng thái FINISH sang target.
            return requireAuction(connection, auctionId);
        }));
    }

    private int refreshOne(long id, long now) {
        List<AuctionDto> changed = locked(id, () -> { //Hàm này ktra riêng 1 phiên đấu giá(có khóa locked)
            List<AuctionDto> committed = Transactions.write(database, connection -> {
                AuctionDto auction = requireAuction(connection, id);
                java.util.ArrayList<AuctionDto> result = new java.util.ArrayList<>();
                if (auction.status() == AuctionStatus.OPEN && now >= auction.startAtEpochMs()) {
                    if (auctions.changeStatus(connection, id, AuctionStatus.OPEN, AuctionStatus.RUNNING)) {
                        auction = requireAuction(connection, id);
                        result.add(auction);
                    }
                }//Nếu đang OPEN và now >= startAtEpochMs() -> đổi trong db từ OPEN thành RUNNING lấy bản ghi mới nhâ nhét vào danh sách result.
                if (auction.status() == AuctionStatus.RUNNING && now >= auction.endAtEpochMs()) {
                    if (auctions.changeStatus(connection, id, AuctionStatus.RUNNING,
                            AuctionStatus.FINISHED)) {
                        result.add(requireAuction(connection, id));
                    }
                }//Ttu nếu tm đk -> đổi RUNNING thành FINISHED, nhét vào result
                return result;
            });
            for (AuctionDto auction : committed) {
                publisher.enqueue(event(auction.status() == AuctionStatus.RUNNING
                        ? EventType.AUCTION_STARTED : EventType.AUCTION_FINISHED, auction, null));
            }
            return committed;
        });
        publisher.drain(id);
        return changed.size(); //Duyệt danh sách các trạng thái cừa đổi, xếp hàng sự kiện(RUNNING vào AUCTION_STARTED, ngc lại vào A_F).CUối cùng ra ngoài khóa gọi publisher.drain(id) và trả về số lần đổi trạng thái changed.size().
    }


    //Các hàm tiện ích nhỏ
    //1.Hàm bọc khóa locked
    private <T> T locked(long auctionId, Callable<T> action) {
        try {
            return manager.withAuctionLock(auctionId, action);
        } catch (RuntimeException ex) { //Gặp lỗi chủ động ném ra->bắt ở đây.
            throw ex;
        } catch (Exception ex) {//Gặp lỗi bât ngờ không phải RuntimeException thì bắt ở đây.
            throw new ServiceException(ErrorCode.INTERNAL_ERROR, "Auction operation failed", ex);
        }
    }

    //2.Hàm tắt để phát sự kiện.(cần phát ngay lập tức 1 sự kiện đang ở ngoài khối hóa)
    private void publish(EventType type, AuctionDto auction, BidDto bid) {
        publisher.enqueue(event(type, auction, bid)); //đóng gói hết bản tin, xếp vào hàng đợi
        publisher.drain(auction.id());//cho xả(drain)
    }


    //3.Hàm đóng gói bản tin JSON(chế tạo 1 phong bì để gửi qua cho Client)
    private EventMessage event(EventType type, AuctionDto auction, BidDto bid) {
        JsonObject payload = new JsonObject(); //tạo ra 1 đối tượng JSON rỗng
        payload.add("auction", gson.toJsonTree(auction)); //biến đối tượng Java AuctionDto thành 1 nhánh của JSON rồi gắn vào khóa "auction".
        if (bid != null) {
            payload.add("bid", gson.toJsonTree(bid));
        }//Nếu sk có kèm thông tin lượt đặt giá (bid != null) -> gắn thêm nhánh bid.Ngc lại thì bỏ qua.
        return new EventMessage(type, auction.id(), payload);//Tạo ra payload rồi đóng gói vào EventMessage.
    }
}
