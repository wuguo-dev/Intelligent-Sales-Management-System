package com.haowugou.domain.salesimport;

import java.math.BigDecimal;

/**
 * 一条待落库的库存流水，已按商品汇总净销量，保证每个商品每批次最多一条。
 *
 * <p>{@code balance_before}/{@code balance_after} 由持久化层读取当前库存后计算，
 * 以满足 {@code chk_inventory_movement_balance}。
 *
 * @param barcode        条码；持久化层据此解析商品主键
 * @param quantityChange 有符号库存变化量，出库为负、退货为正，恒不为 0
 * @param movementType   {@link #TYPE_SALE_OUT} 或 {@link #TYPE_SALE_RETURN}
 */
public record SalesMovement(
        String barcode,
        BigDecimal quantityChange,
        String movementType) {

    public static final String TYPE_SALE_OUT = "SALE_OUT";
    public static final String TYPE_SALE_RETURN = "SALE_RETURN";
}