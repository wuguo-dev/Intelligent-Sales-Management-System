package com.haowugou.infrastructure.persistence.salesimport;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 待完善商品（{@code product.data_status='PENDING'}）批量插入的载体。
 *
 * <p>销售文件里出现了系统未知的条码时先建这样一行，让销售事实能够入账；
 * 单位、备注等字段留空，等商品资料维护补齐。
 */
@Getter
@Setter
public class PendingProductObject {

    private String barcode;
    private String productName;
    private Long categoryId;
    private BigDecimal taxCostPrice;
    private BigDecimal salePrice;
}
