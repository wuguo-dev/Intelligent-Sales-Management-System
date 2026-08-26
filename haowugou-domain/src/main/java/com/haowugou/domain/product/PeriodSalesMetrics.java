package com.haowugou.domain.product;

import java.math.BigDecimal;

/** 指定门店、商品和日期范围内的销售指标。 */
public record PeriodSalesMetrics(
        BigDecimal salesQuantity,
        BigDecimal salesAmount,
        BigDecimal grossProfitAmount) {
}
