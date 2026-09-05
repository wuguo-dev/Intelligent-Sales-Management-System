package com.haowugou.controller.importbatch;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.application.importbatch.ImportBatchDetailResult;
import com.haowugou.domain.importbatch.ImportBatchDetail;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次详情的 HTTP 响应模型：批次元信息 + 分页问题行。
 *
 * @param store 批次所属门店
 * @param batch 批次元信息
 * @param problemRows 当前页问题行（parse_status 非 VALID）
 */
public record ImportBatchDetailResponse(
        ImportStoreResponse store,
        Batch batch,
        ProblemRowPage problemRows) {

    static ImportBatchDetailResponse from(ImportBatchDetailResult result) {
        return new ImportBatchDetailResponse(
                ImportStoreResponse.from(result.store()),
                Batch.from(result.batch()),
                ProblemRowPage.from(result));
    }

    /**
     * 批次元信息。
     *
     * @param batchId 批次主键
     * @param importType INITIAL_INVENTORY / DAILY_SALES
     * @param status VALIDATING / POSTING / POSTED / REVERSED / FAILED
     * @param dataDate 数据归属日期
     * @param fileName 原始文件名
     * @param fileHash 文件 SHA-256 指纹，供核对是否同一份文件
     * @param totalRows 原始数据行数
     * @param successRows 成功行数
     * @param errorRows 错误行数
     * @param errorMessage 批次错误摘要；无错误为 null
     * @param operatorName 导入操作人
     * @param importedAt 上传时间
     * @param postedAt 入账时间；未入账为 null
     * @param reversedAt 撤销时间；未撤销为 null
     * @param reversedBy 撤销操作人；未撤销为 null
     * @param reversedReason 撤销原因；未撤销为 null
     * @param reversible 是否可撤销
     */
    public record Batch(
        @JsonSerialize(using = ToStringSerializer.class) long batchId,
            String importType,
            String status,
            LocalDate dataDate,
            String fileName,
            String fileHash,
            int totalRows,
            int successRows,
            int errorRows,
            String errorMessage,
            String operatorName,
            LocalDateTime importedAt,
            LocalDateTime postedAt,
            LocalDateTime reversedAt,
            String reversedBy,
            String reversedReason,
            boolean reversible) {

        static Batch from(ImportBatchDetail batch) {
            return new Batch(
                    batch.batchId(),
                    batch.importType().name(),
                    batch.status().name(),
                    batch.dataDate(),
                    batch.fileName(),
                    batch.fileHash(),
                    batch.totalRows(),
                    batch.successRows(),
                    batch.errorRows(),
                    batch.errorMessage(),
                    batch.operatorName(),
                    batch.importedAt(),
                    batch.postedAt(),
                    batch.reversedAt(),
                    batch.reversedBy(),
                    batch.reversedReason(),
                    batch.reversible());
        }
    }

    /**
     * 问题行分页块。
     *
     * @param items 当前页问题行
     * @param page 当前页码，从 0 开始
     * @param size 每页数量
     * @param totalElements 问题行总数
     * @param totalPages 总页数
     */
    public record ProblemRowPage(
            List<ImportBatchProblemRowResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        static ProblemRowPage from(ImportBatchDetailResult result) {
            return new ProblemRowPage(
                    result.problemRows().items().stream()
                            .map(ImportBatchProblemRowResponse::from)
                            .toList(),
                    result.problemRows().page(),
                    result.problemRows().size(),
                    result.problemRows().totalElements(),
                    result.problemRows().totalPages());
        }
    }
}
