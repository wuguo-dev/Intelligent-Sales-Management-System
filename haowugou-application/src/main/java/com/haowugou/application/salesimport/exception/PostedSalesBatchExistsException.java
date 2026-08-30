package com.haowugou.application.salesimport.exception;

import java.time.LocalDate;

/** 该门店该业务日期已有 POSTED 销售批次，需先撤销后才能重新导入。对应 HTTP 409。 */
public final class PostedSalesBatchExistsException extends RuntimeException {

    public PostedSalesBatchExistsException(long storeId, LocalDate businessDate) {
        super("门店 " + storeId + " 在 " + businessDate + " 已有有效销售批次");
    }
}
