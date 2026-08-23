package com.haowugou.controller.operating;

import com.haowugou.domain.inventory.InventoryItem;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存快照中单个商品的 HTTP 响应模型。

 * @param productId 商品标识
 * @param barcode 商品条码
 * @param productName 商品名称
 * @param unit 计量单位
 * @param categoryCode 分类编码
 * @param categoryName 分类名称
 * @param snapshotDate 库存快照日期
 * @param quantity 快照库存数量
 * @param unitCost 单位成本
 * @param salePrice 销售价
 * @param dataOrigin 数据来源，例如 {@code DEMO}
 */
public record InventoryItemResponse(
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

    static InventoryItemResponse from(InventoryItem item) {
        return new InventoryItemResponse(
                item.productId(),
                item.barcode(),
                item.productName(),
                item.unit(),
                item.categoryCode(),
                item.categoryName(),
                item.snapshotDate(),
                item.quantity(),
                item.unitCost(),
                item.salePrice(),
                item.dataOrigin());
    }
}
