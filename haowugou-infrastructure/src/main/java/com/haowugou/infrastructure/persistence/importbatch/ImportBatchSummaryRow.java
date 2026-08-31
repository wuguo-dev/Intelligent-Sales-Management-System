package com.haowugou.infrastructure.persistence.importbatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code import_batch} 一行的查询载体，列表与详情共用。
 *
 * <p>列表查询不取 {@code fileHash}/{@code errorMessage}/撤销审计字段，那些列在列表语句里
 * 不 SELECT，留 null 由适配器决定是否读取。
 */
@Getter
@Setter
public class ImportBatchSummaryRow {

    private long batchId;
    private long storeId;
    private String importType;
    private String status;
    private LocalDate dataDate;
    private String fileName;
    private String fileHash;
    private long totalRows;
    private long successRows;
    private long errorRows;
    private String operatorName;
    private String errorMessage;
    private LocalDateTime importedAt;
    private LocalDateTime postedAt;
    private LocalDateTime reversedAt;
    private String reversedBy;
    private String reversedReason;
}
