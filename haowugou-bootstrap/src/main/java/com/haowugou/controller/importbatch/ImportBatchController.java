package com.haowugou.controller.importbatch;

import com.haowugou.application.importbatch.ImportBatchQuery;
import com.haowugou.application.importbatch.ReverseImportBatch;
import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.importbatch.ImportType;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店范围导入批次管理的 HTTP 入口：查列表、查详情、撤销。
 *
 * <p>只做 HTTP 参数绑定与响应模型转换，门店校验、分页校验与撤销编排由
 * {@link ImportBatchQuery} 与 {@link ReverseImportBatch} 完成。
 * 路径显式携带门店标识，批次不属于该门店时按 404 处理，不泄露其他门店批次的存在。
 */
@RestController
@RequestMapping("/api/stores/{storeId}/import-batches")
public class ImportBatchController {

    private final ImportBatchQuery importBatchQuery;
    private final ReverseImportBatch reverseImportBatch;

    public ImportBatchController(
            ImportBatchQuery importBatchQuery,
            ReverseImportBatch reverseImportBatch) {
        this.importBatchQuery = importBatchQuery;
        this.reverseImportBatch = reverseImportBatch;
    }

    /**
     * 返回指定门店的导入批次分页列表，按上传时间倒序。
     *
     * <p>支持按导入类型、批次状态和数据日期区间筛选；日期上下界可单独给。
     */
    @GetMapping
    public ImportBatchPageResponse listBatches(
            @PathVariable long storeId,
            @RequestParam(required = false) ImportType importType,
            @RequestParam(required = false) ImportBatchStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataDateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ImportBatchQueryCriteria criteria = new ImportBatchQueryCriteria(
                storeId, importType, status, dataDateFrom, dataDateTo, page, size);
        return ImportBatchPageResponse.from(importBatchQuery.listBatches(criteria));
    }

    /**
     * 返回单个批次的详情与问题行；批次不存在或不属于该门店时返回 HTTP 404。
     *
     * <p>分页参数只作用于问题行——失败批次的问题行可能上千条。
     */
    @GetMapping("/{batchId}")
    public ImportBatchDetailResponse findBatch(
            @PathVariable long storeId,
            @PathVariable long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ImportBatchDetailResponse.from(
                importBatchQuery.findBatch(storeId, batchId, page, size));
    }

    /**
     * 撤销已入账批次：批次翻 REVERSED、写反向流水、回滚库存。
     *
     * <p>批次不存在或不属于该门店返回 404，状态不是 POSTED（含并发下已被撤销）返回 409，
     * 操作人或原因缺失返回 400。撤销后同一份文件可以重传。
     */
    @PostMapping("/{batchId}/reverse")
    public ReverseImportBatchResponse reverseBatch(
            @PathVariable long storeId,
            @PathVariable long batchId,
            @RequestBody(required = false) ReverseImportBatchRequest request) {
        return ReverseImportBatchResponse.from(reverseImportBatch.reverse(
                storeId,
                batchId,
                request == null ? null : request.reversedBy(),
                request == null ? null : request.reversedReason()));
    }
}
