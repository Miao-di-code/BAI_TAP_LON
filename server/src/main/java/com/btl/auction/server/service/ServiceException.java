package com.btl.auction.server.service;

import com.btl.auction.common.protocol.ErrorCode;

public class ServiceException extends RuntimeException { //Tính kế thừa: Runtime là ngoại lệ Unchecked-> có thể throw ở bất kì đâu trong các hàm Lambda mà không phải viết throw ServiceException.
    private final ErrorCode code;

    public ServiceException(ErrorCode code, String message) {//Tính đa hình: Nạp chồng
        super(message);
        this.code = code;
    }

    public ServiceException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }//Tinh đóng gói: biến được để private lấy qua getter. Các tầng khác lấy mã lỗi ra ở hàm này, đóng gói thành file JSON và gửi qua network về cho màn hình client.
}
