package com.haowugou.controller.salesimport;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.domain.importbatch.ImportRowError;
import com.haowugou.domain.salesimport.DailySalesImportResult;
import java.util.List;

/**
 * 每日销售导入的 HTTP 响应模型：批次主键、终态与行数摘要。
 *
 * @param batchId 批次主键（POSTED 或 FAILED 均返回）
 * @param status 终态：POSTED / FAILED
 * @param totalRows 原始数据行数（不含表头、空行与合计行）
 * @param successRows 通过行级校验的行数（FAILED 时恒为 0）
 * @param errorRows 错误行数（POSTED 时恒为 0）
 * @param salesRows 落库的销售事实条数（数量与收入同时为 0 的行不计入）
 * @param pendingProductsCreated 因未知条码新建的待完善商品数
 * @param deductedProducts 产生库存流水的商品数（净销量为 0 的商品不计入）
 * @param errors 行级错误明细，最多返回 50 条
 */
public record DailySalesImportResponse(
        @JsonSerialize(using = ToStringSerializer.class) long batchId,
        String status,
        int totalRows,
        int successRows,
        int errorRows,
        int salesRows,
        int pendingProductsCreated,
        int deductedProducts,
        List<RowError> errors) {

    private static final int MAX_ERRORS = 50;

    static DailySalesImportResponse from(DailySalesImportResult result) {
        return new DailySalesImportResponse(
                result.batchId(),
                result.status(),
                result.totalRows(),
                result.successRows(),
                result.errorRows(),
                result.salesRows(),
                result.pendingProductsCreated(),
                result.deductedProducts(),
                result.errors().stream()
                        .limit(MAX_ERRORS)
                        .map(RowError::from)
                        .toList());
    }

    /** 行级错误条目：Excel 行号、条码与原因。 */
    public record RowError(long rowNumber, String barcode, String message) {

        static RowError from(ImportRowError error) {
            return new RowError(error.rowNumber(), error.barcode(), error.message());
        }
    }
}
