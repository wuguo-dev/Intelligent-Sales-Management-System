package com.haowugou.domain.product;

import java.math.BigDecimal;
import java.util.List;

/** 指定门店内的商品详情及门店库存信息。 */
public record StoreProductDetail(
        long productId,
        String barcode,
        String productName,
        String unit,
        Long categoryId,
        String categoryCode,
        String categoryName,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        BigDecimal taxCostPrice,
        BigDecimal salePrice,
        String remarks,
        List<String> supplierNames,
        BigDecimal currentQuantity,
        InventoryStatus inventoryStatus,
        ProductDataStatus dataStatus,
        PeriodSalesMetrics periodSalesMetrics) {

    public StoreProductDetail {
        supplierNames = supplierNames == null ? List.of() : List.copyOf(supplierNames);
    }
}
