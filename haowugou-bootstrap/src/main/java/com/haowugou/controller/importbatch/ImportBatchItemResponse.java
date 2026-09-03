package com.haowugou.controller.importbatch;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.domain.importbatch.ImportBatchListItem;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批次列表条目。
 *
 * @param batchId 批次主键
 * @param importType INITIAL_INVENTORY / DAILY_SALES
 * @param status VALIDATING / POSTING / POSTED / REVERSED / FAILED
 * @param dataDate 数据归属日期；销售导入为业务日期
 * @param fileName 原始文件名
 * @param totalRows 原始数据行数
 * @param successRows 成功行数
 * @param errorRows 错误行数
 * @param importedAt 上传时间
 * @param postedAt 入账时间；未入账为 null
 * @param reversedAt 撤销时间；未撤销为 null
 * @param reversible 是否可撤销，供前端决定按钮是否可点
 */
public record ImportBatchItemResponse(
        @JsonSerialize(using = ToStringSerializer.class) long batchId,
        String importType,
        String status,
        LocalDate dataDate,
        String fileName,
        int totalRows,
        int successRows,
        int errorRows,
        LocalDateTime importedAt,
        LocalDateTime postedAt,
        LocalDateTime reversedAt,
        boolean reversible) {

    static ImportBatchItemResponse from(ImportBatchListItem item) {
        return new ImportBatchItemResponse(
                item.batchId(),
                item.importType().name(),
                item.status().name(),
                item.dataDate(),
                item.fileName(),
                item.totalRows(),
                item.successRows(),
                item.errorRows(),
                item.importedAt(),
                item.postedAt(),
                item.reversedAt(),
                item.reversible());
    }
}
