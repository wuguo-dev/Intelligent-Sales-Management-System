package com.haowugou.controller.product;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.application.product.StoreProductDetailResult;
import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.PeriodSalesMetrics;
import com.haowugou.domain.product.ProductDataStatus;
import com.haowugou.domain.product.StoreProductDetail;
import java.math.BigDecimal;
import java.util.List;

/**
 * 门店商品详情的 HTTP 响应模型。
 *
 * <p>期间销售指标在未指定日期范围时为 {@code null}，不假定统计全部历史。
 *
 * @param store 查询范围所属门店
 * @param productId 商品标识
 * @param barcode 商品条码
 * @param productName 商品名称
 * @param unit 计量单位
 * @param categoryId 品类标识
 * @param categoryCode 品类编码
 * @param categoryName 品类名称
 * @param warehouseId 该门店库存关系的仓库标识，允许为空
 * @param warehouseCode 仓库编码
 * @param warehouseName 仓库名称
 * @param taxCostPrice 含税成本价
 * @param salePrice 销售价
 * @param remarks 备注
 * @param supplierNames 全部关联供应商名称
 * @param currentQuantity 该门店当前库存数量
 * @param inventoryStatus 当前库存状态
 * @param dataStatus 商品资料状态
 * @param periodSalesQuantity 期间销量
 * @param periodSalesAmount 期间销售额
 * @param periodGrossProfitAmount 期间毛利额
 */
public record StoreProductDetailResponse(
        StoreSummaryResponse store,
        @JsonSerialize(using = ToStringSerializer.class) Long productId,
        String barcode,
        String productName,
        String unit,
        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        String categoryCode,
        String categoryName,
        @JsonSerialize(using = ToStringSerializer.class) Long warehouseId,
        String warehouseCode,
        String warehouseName,
        BigDecimal taxCostPrice,
        BigDecimal salePrice,
        String remarks,
        List<String> supplierNames,
        BigDecimal currentQuantity,
        InventoryStatus inventoryStatus,
        ProductDataStatus dataStatus,
        BigDecimal periodSalesQuantity,
        BigDecimal periodSalesAmount,
        BigDecimal periodGrossProfitAmount) {

    static StoreProductDetailResponse from(StoreProductDetailResult result) {
        StoreProductDetail product = result.product();
        PeriodSalesMetrics metrics = product.periodSalesMetrics();
        return new StoreProductDetailResponse(
                StoreSummaryResponse.from(result.store()),
                product.productId(),
                product.barcode(),
                product.productName(),
                product.unit(),
                product.categoryId(),
                product.categoryCode(),
                product.categoryName(),
                product.warehouseId(),
                product.warehouseCode(),
                product.warehouseName(),
                product.taxCostPrice(),
                product.salePrice(),
                product.remarks(),
                product.supplierNames(),
                product.currentQuantity(),
                product.inventoryStatus(),
                product.dataStatus(),
                metrics == null ? null : metrics.salesQuantity(),
                metrics == null ? null : metrics.salesAmount(),
                metrics == null ? null : metrics.grossProfitAmount());
    }
}