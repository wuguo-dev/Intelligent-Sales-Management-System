package com.haowugou.domain.sales;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 门店日销售。
 */
public record StoreDailySales(
        Long id,
        Long storeId,
        LocalDate businessDate,
        BigDecimal totalSalesAmount,
        int orderCount,
        BigDecimal refundAmount,
        BigDecimal grossProfitAmount,
        String dataOrigin) {
}
