package com.haowugou.infrastructure.persistence.salesimport;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code inventory_movement} 表批量插入的载体（销售出库/退货）。
 *
 * <p>与初始库存导入不同，这里的 {@code movementType} 按商品净销量方向逐行取
 * {@code SALE_OUT} 或 {@code SALE_RETURN}，因此不能写死在 SQL 里。
 * {@code balanceAfter} 必须等于 {@code balanceBefore + quantityChange}，
 * 由适配器按「先 SELECT 现有余额 → Java 计算」保证，满足 {@code chk_inventory_movement_balance}。
 */
@Getter
@Setter
public class SalesMovementObject {

    private long productId;
    private String movementType;
    private BigDecimal quantityChange;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
}
