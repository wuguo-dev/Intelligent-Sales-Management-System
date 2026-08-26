package com.haowugou.domain.product;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 门店商品分页查询的完整条件，所有查询必须携带门店标识。 */
public record StoreProductQueryCriteria(
        long storeId,
        String keyword,
        Long categoryId,
        Long supplierId,
        Long warehouseId,
        InventoryStatus inventoryStatus,
        ProductDataStatus dataStatus,
        BigDecimal minStock,
        BigDecimal maxStock,
        LocalDate startDate,
        LocalDate endDate,
        int page,
        int size) {

    public boolean hasSalesPeriod() {
        return startDate != null && endDate != null;
    }
}
