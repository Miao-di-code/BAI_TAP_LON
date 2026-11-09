package com.btl.auction.server.service;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class AuctionManager {//Tác dụng: gác cổng đa luồng.
    private static final AuctionManager INSTANCE = new AuctionManager(); //Tự tạo 1 bản sao
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();//Là chiếc tủ khóa. Nó ánh xạ ID với chiếc ổ khóa tương ứng(ReentrantLock)

    private AuctionManager() { //Khóa hàm khởi tạo
    }

    public static AuctionManager getInstance() {
        return INSTANCE;
    }//Nếu ai muốn dùng chỉ lấy đợc cái đã tạo ra duy nhất

    //Mtk: Singleton

    public <T> T withAuctionLock(long auctionId, Callable<T> action) throws Exception {//tính đóng gói:locks là private.AuctionService muốn dùng khóa phải đi qua hàm này.
        //AuctionManager không cần biết AuctionService sẽ làm gì -> trừu tượng hóa các cv đó thành action.
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        Objects.requireNonNull(action, "action");
        ReentrantLock lock = locks.computeIfAbsent(auctionId, ignored -> new ReentrantLock());//locks.computeIfAbsent kiểm tra phiên đã có khóa chưa -> chưa thì sinh ra đúng 1 cái mới cho phiên đó.
        lock.lock();//luồng nào vào trước rồi thì khóa lại ngay rồi vào try.Các luồng khác phải chờ
        try {
            return action.call();
        } finally {
            lock.unlock(); //Luôn luôn chạy: ổ khóa được mở ra để luồng khác vào
        }
    }
}
