package com.haowugou.infrastructure.persistence.data;

import lombok.Getter;
import lombok.Setter;

/** 批量查询商品供应商名称的结果投影。 */
@Getter
@Setter
public class ProductSupplierRow {

    private Long productId;
    private String supplierName;
}
