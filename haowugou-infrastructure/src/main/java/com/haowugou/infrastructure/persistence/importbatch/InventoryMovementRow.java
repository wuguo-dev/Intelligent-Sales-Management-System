package com.haowugou.infrastructure.persistence.importbatch;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code inventory_movement} 表批量插入的载体。
 *
 * <p>{@code balanceAfter} 必须等于 {@code balanceBefore + quantityChange}，
 * 由适配器按「先 SELECT 现有余额 → Java 计算」保证，满足 {@code chk_inventory_movement_balance}。
 */
@Getter
@Setter
public class InventoryMovementRow {

    private long productId;
    private BigDecimal quantityChange;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
}
