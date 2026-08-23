package com.haowugou.domain.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 某个门店在指定日期的单个商品库存。
 */
public record InventoryItem(
        Long storeId,
        Long productId,
        String barcode,
        String productName,
        String unit,
        String categoryCode,
        String categoryName,
        LocalDate snapshotDate,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal salePrice,
        String dataOrigin) {
}
