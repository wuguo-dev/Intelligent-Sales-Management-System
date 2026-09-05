package com.haowugou.application.operating.exception;

/**
 * 请求的门店不存在或未启用。
 */
public final class StoreNotFoundException extends RuntimeException {

    public StoreNotFoundException(long storeId) {
        super("门店不存在或未启用: " + storeId);
    }
}
