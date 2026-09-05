package com.haowugou.infrastructure.persistence.importbatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code import_batch} 表的持久化对象，仅在基础设施层使用。
 *
 * <p>{@code id} 由数据库自增生成，插入后由 MyBatis {@code useGeneratedKeys} 回填。
 * 两个生成列（{@code active_sales_date}、{@code active_initial_inventory}）由数据库维护，不在此处赋值。
 */
@Getter
@Setter
public class ImportBatchDataObject {

    private Long id;
    private long storeId;
    private String importType;
    private LocalDate dataDate;
    private String fileName;
    private String fileHash;
    private String status;
    private int totalRows;
    private int successRows;
    private int errorRows;
    private String errorMessage;
    private LocalDateTime postedAt;
}
