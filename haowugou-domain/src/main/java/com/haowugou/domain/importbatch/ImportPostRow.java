package com.haowugou.domain.importbatch;

import java.math.BigDecimal;

/**
 * 通过全部校验、需要正式过账的数据行。
 *
 * @param barcode   条码
 * @param productId 条码对应的商品主键（应用层已归并）
 * @param quantity  库存数量（大于 0，最多 3 位小数）
 */
public record ImportPostRow(
        long rowNumber,
        String barcode,
        long productId,
        BigDecimal quantity) {
}
