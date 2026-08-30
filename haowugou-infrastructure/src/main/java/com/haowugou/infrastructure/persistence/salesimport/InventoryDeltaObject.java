package com.haowugou.infrastructure.persistence.salesimport;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店商品库存增量：销售扣减为负、退货为正。
 *
 * <p>库存行可能尚不存在（待完善商品、或从未导过初始库存的商品），此时插入新行、
 * 仓库留空待分配，允许形成负库存。
 */
@Getter
@Setter
public class InventoryDeltaObject {

    private long productId;
    private BigDecimal quantityChange;
}
