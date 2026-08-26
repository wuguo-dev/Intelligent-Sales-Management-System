package com.haowugou.infrastructure.persistence.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 库存快照与商品联表查询的结果投影。 */
@Getter
@Setter
public class InventoryItemRow {

    private Long storeId;
    private Long productId;
    private String barcode;
    private String productName;
    private String unit;
    private String categoryCode;
    private String categoryName;
    private LocalDate snapshotDate;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal salePrice;
    private String dataOrigin;
}
