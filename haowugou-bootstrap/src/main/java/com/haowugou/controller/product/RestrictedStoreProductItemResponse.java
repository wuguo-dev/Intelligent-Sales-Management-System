package com.haowugou.controller.product;

import com.haowugou.domain.product.StoreProductListItem;
import java.math.BigDecimal;

/**
 * 普通用户可见的门店商品列表项。
 *
 * <p>普通用户的可见范围被限定为售价、库存数量与所处仓库，因此本模型是
 * {@link StoreProductItemResponse} 的真子集：不含含税成本价、供应商、品类、
 * 商品资料状态与期间销售指标。字段名与完整模型保持一致，前端同一套渲染逻辑
 * 只是拿到的字段更少，不需要按角色分叉。
 *
 * <p>做成独立记录而不是把完整模型的字段置空，是为了让「看不到」在类型上成立：
 * 置空要靠每个字段的赋值点自觉，漏一个就直接泄露；少声明的字段则连序列化的
 * 机会都没有。
 *
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
public record RestrictedStoreProductItemResponse(
        Long productId,
        String barcode,
        String productName,
        String unit,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        BigDecimal salePrice,
        BigDecimal currentQuantity) {

    static RestrictedStoreProductItemResponse from(StoreProductListItem item) {
        return new RestrictedStoreProductItemResponse(
                item.productId(),
                item.barcode(),
                item.productName(),
                item.unit(),
                item.warehouseId(),
                item.warehouseCode(),
                item.warehouseName(),
                item.salePrice(),
                item.currentQuantity());
    }
}
