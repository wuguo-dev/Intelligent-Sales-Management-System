package com.haowugou.application.salesimport;

/** 同一销售文件（同门店、DAILY_SALES、同 SHA-256）已导入过。对应 HTTP 409。 */
public final class DuplicateSalesFileException extends RuntimeException {

    public DuplicateSalesFileException(String fileName) {
        super("该销售文件已导入过: " + fileName);
    }
}
