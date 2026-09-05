package com.haowugou.infrastructure.persistence.warehouse;

import lombok.Getter;
import lombok.Setter;

/** 仓库查询结果投影。 */
@Getter
@Setter
public class WarehouseRow {

    private Long id;
    private Long storeId;
    private String warehouseCode;
    private String warehouseName;
}
