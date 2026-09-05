package com.haowugou.infrastructure.persistence.importbatch;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 过账前读取的现有库存余额投影。 */
@Getter
@Setter
public class InventoryQuantityRow {

    private Long productId;
    private BigDecimal currentQuantity;
}
