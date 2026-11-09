package com.btl.auction.server.event;

import com.btl.auction.common.protocol.EventMessage;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
//Tổng đài phát thanh(the Subject trong mkt Observer).
public final class AuctionEventPublisher {
    private static final Logger LOGGER = Logger.getLogger(AuctionEventPublisher.class.getName());//ghi nhật ký lỗi hệ thống
    private static final class PendingEvents {
        private final ConcurrentLinkedQueue<EventMessage> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();//Một cờ hiệu(Atomic flag) mang gtri TRUE/FALSE. Cờ này đánh dấu: "Hiện có luồng nào đang trực tiếp xả hàng đợi của phiên này không?". Giúp các luồng tự điều phối mà ko cần dùng ổ khóa chặn(ReentrantLock)
    }

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<AuctionListener>> listeners =
            new ConcurrentHashMap<>(); //listeners: cuốn sổ ghi khán giả;Khóa (Long) là auctionId, giá trị là một danh sách CopyOnWriteArrayList chứa các AuctionListener đang xem phiên đó.
    private final ConcurrentHashMap<Long, PendingEvents> pending = new ConcurrentHashMap<>(); //bản đồ qly các hàng đợi sk chưa phát. Mỗi auctionId sử hữu 1 đối tượng PendingEvents riêng.

    public void enqueue(EventMessage event) {//Bỏ thư vào hàng đợi
        Objects.requireNonNull(event, "event");
        pending.computeIfAbsent(event.auctionId(), ignored -> new PendingEvents()).queue.add(event);//Ktra phiên đấu giá này có hàng đợi PendingEvent chưa -> Nếu chưa tạo 1 PendingEvents và đẩy sự kiện vào cuối hàng đợi queue.
    }

    public void drain(long auctionId) {//Xả van
        PendingEvents state = pending.get(auctionId); //Tìm hàng đợi sk của phiên tương ứng.
        if (state == null || !state.draining.compareAndSet(false, true)) {
            return;
        }//Nếu ko có sk(state == null), dừng lại; state.draining.compareAndSet: lệnh này ktra nếu cờ draining đang là false(chưa ai xả) thì đổi thành true (chiếm quyền xả) .Nếu đổi thất bại(nghĩa là có luồng khác đang xả) return ngay tránh 2 luồng tranh nhau.
        for (;;) { //for(;;) : Một vòng lặp vô tận để xử lý htg đua dữ liệu
            try {
                EventMessage event;
                while ((event = state.queue.poll()) != null) {
                    publish(event);
                } //lấy liên tục các tin trong hàng đợi ra phát thanh qua hàm publish
            } finally {
                state.draining.set(false); //Sau khi rút hết hàng đợi đổi cờ thành lại false để báo hiệu phiên xả kết thúc.
            }
            if (state.queue.isEmpty() || !state.draining.compareAndSet(false, true)) {
                return;
            } //Kiểm tra lại: nếu hàng đợi trống hoặc cờ bị chiếm -> luồng này return. Ngược lại nếu thấy còn tin sót và chiếm cờ thành công thì vòng for(;;) sẽ quay lại đầu để vét sạch những tin mới đến.
        }
    }

    public void subscribe(long auctionId, AuctionListener listener) {//Quản lý đăng ký
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        listeners.computeIfAbsent(auctionId, ignored -> new CopyOnWriteArrayList<>())
                .addIfAbsent(Objects.requireNonNull(listener, "listener")); //computeIfAbsent: Tạo danh sách nếu phiên chưa có ai theo dõi. addIfAbsent: mỗi 1 kết nối listener chỉ được đk đúng 1 lần cho 1 phiên.
    }

    public void unsubscribe(long auctionId, AuctionListener listener) {
        CopyOnWriteArrayList<AuctionListener> auctionListeners = listeners.get(auctionId);
        if (auctionListeners != null) {
            auctionListeners.remove(listener); //Xóa người nghe khỏi danh sách phiên.
            if (auctionListeners.isEmpty()) {
                listeners.remove(auctionId, auctionListeners);
            } //Khi toàn bộ người nghe rời đi, auctionId này sẽ bị xóa khỏi listeners ->giải phóng Ram, tránh giữ các phiên đấu giá cũ.
        }
    }

    public void publish(EventMessage event) {//Gửi tin đến từng người nghe
        Objects.requireNonNull(event, "event");
        CopyOnWriteArrayList<AuctionListener> auctionListeners = listeners.get(event.auctionId()); //Lấy danh sách ai đang nghe phiên đấu giá.
        if (auctionListeners != null) {
            for (AuctionListener listener : auctionListeners) {
                try {
                    listener.onAuctionEvent(event); //Duyệt qua từng listener và gọi phương thức onAuctionEvent mà chúng đã tự định nghĩa.
                } catch (RuntimeException ex) {
                    LOGGER.log(Level.WARNING, "Auction listener failed", ex);
                }//Nếu 1 ng bị lỗi catch sẽ bắt lỗi và dòng for vẫn tiếp tục gửi tin cho những người còn lại.
            }
        }
    }
}
