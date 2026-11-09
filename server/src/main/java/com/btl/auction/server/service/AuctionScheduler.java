package com.btl.auction.server.service;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuctionScheduler implements AutoCloseable {//Background Worker của hệ thống.
    //Khi main chạy xong thì chương trình tắt, nhưng Server đấu giá thì phải chạy liên tục. Ta cần một cái đồng hồ cứ 1 giây lại reo 1 lần để hệ thống đi ktra có phiên nào hết giờ không -> chính là lớp này.
    private static final Logger LOGGER = Logger.getLogger(AuctionScheduler.class.getName()); //Dùng Logger để ghi lỗi vào 1 cuốn "sổ nhật ký" log file.
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {//newSingleThreadScheduledExecutor: mở ra đúng 1 luồng chuyên làm nhiệm vụ canh giờ.
        Thread thread = new Thread(r, "auction-clock"); //Tạo ra luồng mới mang theo công việc "r" và mang tên là "auction-clock" .Nếu sau hệ thống lỗi thấy báo "auction-clock" ta biết lỗi do lớp này gây ra.
        thread.setDaemon(true); //Daemon là luồng ngầm -> Khi server tắt thì tắt luồng chạy này luôn.
        return thread;
    });

    public AuctionScheduler(AuctionService service, Clock clock) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(clock, "clock");
        executor.scheduleWithFixedDelay(() -> {
            try {
                service.closeExpired(clock.millis()); //Công việc chính: gọi service.closeExpỉed() để quét db và đóng phiên hết hạn.
            } catch (RuntimeException ex) {
                LOGGER.log(Level.SEVERE, "Auction status refresh failed", ex);
            }
        }, 0, 1, TimeUnit.SECONDS); //Làm việc ngay lập tức(Sau 0 giây); nghỉ 1 giây rồi làm tiếp; TimeUnit.SECONDS: qui định cho 2 cái số 0 và 1 ở trước nó đơn vị là Giây.
    }

    @Override
    public void close() {
        executor.shutdownNow();
    } //tắt luồng.
}
