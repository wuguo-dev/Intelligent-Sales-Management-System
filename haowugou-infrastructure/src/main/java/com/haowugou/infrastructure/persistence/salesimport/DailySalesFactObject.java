package com.haowugou.infrastructure.persistence.salesimport;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code daily_product_sales} 表批量插入的载体，条码已解析为商品主键。
 *
 * <p>{@code supplierId} 为空时数据库生成列 {@code supplier_key} 取 0，
 * 与 {@code uk_daily_sales_batch_product_supplier} 一起保证同批次同商品只有一条「未知供应商」记录。
 */
@Getter
@Setter
public class DailySalesFactObject {

    private long productId;
    private Long supplierId;
    private BigDecimal salesQuantity;
    private BigDecimal salesAmount;
    private BigDecimal grossProfitAmount;
    private BigDecimal reportedRate;
}
