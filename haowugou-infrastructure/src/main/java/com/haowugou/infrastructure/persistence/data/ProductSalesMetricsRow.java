package com.haowugou.infrastructure.persistence.data;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 批量查询门店商品期间销售指标的结果投影。 */
@Getter
@Setter
public class ProductSalesMetricsRow {

    private Long productId;
    private BigDecimal salesQuantity;
    private BigDecimal salesAmount;
    private BigDecimal grossProfitAmount;
}
