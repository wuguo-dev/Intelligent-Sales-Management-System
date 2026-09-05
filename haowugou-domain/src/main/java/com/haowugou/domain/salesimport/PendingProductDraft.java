package com.haowugou.domain.salesimport;

import java.math.BigDecimal;

/**
 * 未知条码对应的待完善商品草稿：销售事实必须入账，因此先建 {@code data_status='PENDING'} 的商品。
 *
 * @param barcode      条码，商品业务唯一键
 * @param productName  商品名称，取文件中该条码首次出现的名称
 * @param categoryId   按品类名称匹配到的现有品类；匹配不到为 null，不自动创建品类
 * @param taxCostPrice 含税成本价初值；无法解析为 null
 * @param salePrice    售价初值；无法解析为 null
 */
public record PendingProductDraft(
        String barcode,
        String productName,
        Long categoryId,
        BigDecimal taxCostPrice,
        BigDecimal salePrice) {
}