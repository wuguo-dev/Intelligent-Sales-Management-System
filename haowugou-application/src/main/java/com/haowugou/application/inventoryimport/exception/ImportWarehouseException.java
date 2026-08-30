package com.haowugou.application.inventoryimport.exception;

/** 导入时指定的仓库非法：主键不合法或不属于该门店。对应 HTTP 400。 */
public final class ImportWarehouseException extends RuntimeException {

    public ImportWarehouseException(long storeId, long warehouseId) {
        super("仓库不属于该门店: storeId=" + storeId + ", warehouseId=" + warehouseId);
    }
}
