package com.haowugou.controller.operating;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.domain.sales.StoreDailySales;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 门店日销售的 HTTP 响应模型。

 * <p>名称中特别保留“门店”，用于和后续可能增加的商品日销售响应区分。响应模型在 HTTP
 * 适配层完成领域对象投影，避免领域层依赖 JSON 字段结构。

 * @param storeId 门店标识
 * @param businessDate 营业日期
 * @param totalSalesAmount 当日销售总额
 * @param orderCount 当日订单数
 * @param refundAmount 当日退款金额
 * @param grossProfitAmount 当日毛利金额
 * @param dataOrigin 数据来源，例如 {@code DEMO}
 */
public record StoreDailySalesResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long storeId,
        LocalDate businessDate,
        BigDecimal totalSalesAmount,
        int orderCount,
        BigDecimal refundAmount,
        BigDecimal grossProfitAmount,
        String dataOrigin) {

    static StoreDailySalesResponse from(StoreDailySales sales) {
        return new StoreDailySalesResponse(
                sales.storeId(),
                sales.businessDate(),
                sales.totalSalesAmount(),
                sales.orderCount(),
                sales.refundAmount(),
                sales.grossProfitAmount(),
                sales.dataOrigin());
    }
}
