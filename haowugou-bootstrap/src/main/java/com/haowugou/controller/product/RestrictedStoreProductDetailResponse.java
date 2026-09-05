package com.haowugou.controller.product;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.application.product.StoreProductDetailResult;
import com.haowugou.domain.product.StoreProductDetail;
import java.math.BigDecimal;

/**
 * 普通用户可见的门店商品详情。
 *
 * <p>字段与 {@link RestrictedStoreProductItemResponse} 相同，只多一个门店信息：
 * 普通用户看详情与看列表的可见范围没有区别，详情页不是提权入口。备注、含税成本价、
 * 供应商、品类与期间指标都不在这里声明。
 *
 * @param store 查询范围所属门店
 * @param productId 商品标识
 * @param barcode 商品条码
 * @param productName 商品名称
 * @param unit 计量单位
 * @param warehouseId 该门店库存关系的仓库标识，允许为空
 * @param warehouseCode 仓库编码
 * @param warehouseName 仓库名称
 * @param salePrice 商品售价
 * @param currentQuantity 该门店当前库存数量
 */
public record RestrictedStoreProductDetailResponse(
        StoreSummaryResponse store,
        @JsonSerialize(using = ToStringSerializer.class) Long productId,
        String barcode,
        String productName,
        String unit,
        @JsonSerialize(using = ToStringSerializer.class) Long warehouseId,
        String warehouseCode,
        String warehouseName,
        BigDecimal salePrice,
        BigDecimal currentQuantity) {

    static RestrictedStoreProductDetailResponse from(StoreProductDetailResult result) {
        StoreProductDetail product = result.product();
        return new RestrictedStoreProductDetailResponse(
                StoreSummaryResponse.from(result.store()),
                product.productId(),
                product.barcode(),
                product.productName(),
                product.unit(),
                product.warehouseId(),
                product.warehouseCode(),
                product.warehouseName(),
                product.salePrice(),
                product.currentQuantity());
    }
}
