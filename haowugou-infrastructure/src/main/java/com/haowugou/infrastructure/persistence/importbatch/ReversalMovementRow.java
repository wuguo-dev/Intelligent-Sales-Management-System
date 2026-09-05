package com.haowugou.infrastructure.persistence.importbatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 待插入的 REVERSAL 流水。
 *
 * <p>{@code quantityChange} 是原流水的相反数，{@code balanceAfter = balanceBefore + quantityChange}
 * 由适配器按商品串起余额链算出，满足 {@code chk_inventory_movement_balance}；
 * {@code reversalOfId} 非空满足 {@code chk_inventory_movement_reversal_ref}。
 */
@Getter
@Setter
public class ReversalMovementRow {

    private long productId;
    private LocalDate businessDate;
    private BigDecimal quantityChange;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private long reversalOfId;
}
