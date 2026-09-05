package com.haowugou.infrastructure.persistence.importbatch;

import lombok.Getter;
import lombok.Setter;

/** 按条码查商品主键的查询投影。 */
@Getter
@Setter
public class ProductIdRow {

    private String barcode;
    private Long productId;
}
