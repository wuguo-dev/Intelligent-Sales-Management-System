package com.haowugou.infrastructure.persistence.data;

import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.ProductDataStatus;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 门店商品库存视图的查询投影。 */
@Getter
@Setter
public class StoreProductRow {

    private Long productId;
    private String barcode;
    private String productName;
    private String unit;
    private Long categoryId;
    private String categoryCode;
    private String categoryName;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private BigDecimal taxCostPrice;
    private BigDecimal salePrice;
    private String remarks;
    private BigDecimal currentQuantity;
    private InventoryStatus inventoryStatus;
    private ProductDataStatus dataStatus;
}
