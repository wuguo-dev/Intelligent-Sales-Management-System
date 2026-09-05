package com.haowugou.domain.salesimport;

import java.math.BigDecimal;

/**
 * 一条待落库的销售事实，已按 {@code (条码, 供应商)} 归并，对应 {@code daily_product_sales} 一行。
 *
 * @param barcode           条码；持久化层据此解析商品主键（含本批次新建的待完善商品）
 * @param supplierId        供应商主键；未识别为 null，库层生成列会归为同一「未知供应商」记录
 * @param salesQuantity     净销售数量，可为负
 * @param salesAmount       净销售收入，可为负
 * @param grossProfitAmount 毛利额，由销售收入与 POS 毛利率推算；无法推算为 null
 * @param reportedRate      POS 原始毛利率百分数，仅供核对；多行归并时为 null
 */
public record DailySalesFactRow(
        String barcode,
        Long supplierId,
        BigDecimal salesQuantity,
        BigDecimal salesAmount,
        BigDecimal grossProfitAmount,
        BigDecimal reportedRate) {
}