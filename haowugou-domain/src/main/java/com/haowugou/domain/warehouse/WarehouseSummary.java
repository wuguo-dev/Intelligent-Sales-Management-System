package com.haowugou.domain.warehouse;

/** 指定门店下可用于商品定位的仓库简要信息。 */
public record WarehouseSummary(
        long id,
        long storeId,
        String warehouseCode,
        String warehouseName) {
}
