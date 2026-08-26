package com.haowugou.controller.product;

import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.PeriodSalesMetrics;
import com.haowugou.domain.product.ProductDataStatus;
import com.haowugou.domain.product.StoreProductListItem;
import java.math.BigDecimal;
import java.util.List;

/**
 * 门店商品分页列表项的 HTTP 响应模型。
 *
 * <p>期间销售指标在未指定日期范围时为 {@code null}，不假定统计全部历史。
 *
 * @param productId 商品标识
 * @param barcode 商品条码
 * @param productName 商品名称
 * @param unit 计量单位
 * @param categoryId 品类标识
 * @param categoryName 品类名称
 * @param warehouseId 该门店库存关系的仓库标识，允许为空
 * @param warehouseCode 仓库编码
 * @param warehouseName 仓库名称
 * @param supplierNames 全部关联供应商名称
 * @param currentQuantity 该门店当前库存数量
 * @param inventoryStatus 当前库存状态
 * @param dataStatus 商品资料状态
 * @param periodSalesQuantity 期间销量
 * @param periodSalesAmount 期间销售额
 * @param periodGrossProfitAmount 期间毛利额
 */
public record StoreProductItemResponse(
        Long productId,
        String barcode,
        String productName,
        String unit,
        Long categoryId,
        String categoryName,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        List<String> supplierNames,
        BigDecimal currentQuantity,
        InventoryStatus inventoryStatus,
        ProductDataStatus dataStatus,
        BigDecimal periodSalesQuantity,
        BigDecimal periodSalesAmount,
        BigDecimal periodGrossProfitAmount) {

    static StoreProductItemResponse from(StoreProductListItem item) {
        PeriodSalesMetrics metrics = item.periodSalesMetrics();
        return new StoreProductItemResponse(
                item.productId(),
                item.barcode(),
                item.productName(),
                item.unit(),
                item.categoryId(),
                item.categoryName(),
                item.warehouseId(),
                item.warehouseCode(),
                item.warehouseName(),
                item.supplierNames(),
                item.currentQuantity(),
                item.inventoryStatus(),
                item.dataStatus(),
                metrics == null ? null : metrics.salesQuantity(),
                metrics == null ? null : metrics.salesAmount(),
                metrics == null ? null : metrics.grossProfitAmount());
    }
}