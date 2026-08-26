package com.haowugou.application.product;

/** 查询使用的仓库不属于指定门店。 */
public final class WarehouseNotInStoreException extends RuntimeException {

    public WarehouseNotInStoreException(long storeId, long warehouseId) {
        super("仓库不属于指定门店: storeId=" + storeId + ", warehouseId=" + warehouseId);
    }
}
