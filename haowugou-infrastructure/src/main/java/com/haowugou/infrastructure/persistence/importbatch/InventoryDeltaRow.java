package com.haowugou.infrastructure.persistence.importbatch;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 撤销时对 {@code store_product_inventory.current_quantity} 的有符号增量。
 *
 * <p>撤销初始库存导入时为负、撤销销售导入时为正。库存允许为负
 * （{@code current_quantity} 无非负约束），所以这里不做下限保护，
 * 由业务侧解释「先卖后撤初始库存」造成的负库存。
 */
@Getter
@Setter
public class InventoryDeltaRow {

    private long productId;
    private BigDecimal quantityDelta;
}
