package com.haowugou.domain.product;

import java.math.BigDecimal;
import java.util.List;

/** 指定门店内的商品列表项。 */
public record StoreProductListItem(
        long productId,
        String barcode,
        String productName,
        String unit,
        Long categoryId,
        String categoryName,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        BigDecimal salePrice,
        List<String> supplierNames,
        BigDecimal currentQuantity,
        InventoryStatus inventoryStatus,
        ProductDataStatus dataStatus,
        PeriodSalesMetrics periodSalesMetrics) {

    public StoreProductListItem {
        supplierNames = supplierNames == null ? List.of() : List.copyOf(supplierNames);
    }
}
