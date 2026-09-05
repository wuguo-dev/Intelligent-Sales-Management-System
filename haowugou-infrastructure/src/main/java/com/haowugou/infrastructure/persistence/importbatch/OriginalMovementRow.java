package com.haowugou.infrastructure.persistence.importbatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 撤销时读出的原流水：一条待冲销的 {@code inventory_movement}。
 *
 * <p>{@code movementId} 会写进反向流水的 {@code reversal_of_id}，
 * {@code uk_inventory_movement_reversal} 因此保证同一条原流水只能被冲销一次。
 */
@Getter
@Setter
public class OriginalMovementRow {

    private long movementId;
    private long productId;
    private LocalDate businessDate;
    private BigDecimal quantityChange;
}
