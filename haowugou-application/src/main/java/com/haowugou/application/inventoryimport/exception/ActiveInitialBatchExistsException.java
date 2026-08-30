package com.haowugou.application.inventoryimport.exception;

/** 门店已有仍有效的初始库存批次，需先撤销后才能再次导入。对应 HTTP 409。 */
public final class ActiveInitialBatchExistsException extends RuntimeException {

    public ActiveInitialBatchExistsException(long storeId) {
        super("门店已有有效初始库存批次: " + storeId);
    }
}
