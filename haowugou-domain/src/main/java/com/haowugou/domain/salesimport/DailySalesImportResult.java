package com.haowugou.domain.salesimport;

import com.haowugou.domain.importbatch.ImportRowError;

import java.util.List;

/**
 * 每日销售导入用例的返回结果：批次主键、终态与行数摘要。
 *
 * @param totalRows             文件数据行数（不含表头、空行与合计行）
 * @param successRows           通过行级校验的行数
 * @param errorRows             行级校验失败的行数
 * @param salesRows             实际落库的销售事实条数（数量与收入同时为 0 的行不计入）
 * @param pendingProductsCreated 因未知条码新建的待完善商品数
 * @param deductedProducts      产生库存流水的商品数（净销量为 0 的商品不计入）
 */
public record DailySalesImportResult(
        long batchId,
        String status,
        int totalRows,
        int successRows,
        int errorRows,
        int salesRows,
        int pendingProductsCreated,
        int deductedProducts,
        List<ImportRowError> errors) {
}