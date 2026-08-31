package com.haowugou.domain.importbatch;

/**
 * 导入批次状态，对应 {@code import_batch.status}（架构规范 §13 状态机）。
 *
 * <p>当前同步过账方案只会落库 {@link #POSTED} 与 {@link #FAILED}；{@link #VALIDATING}
 * 与 {@link #POSTING} 为异步导入预留，用于查询筛选时能表达数据库里的全部取值。
 */
public enum ImportBatchStatus {

    VALIDATING,
    POSTING,
    POSTED,
    REVERSED,
    FAILED;

    /** 只有 POSTED 批次可以撤销。 */
    public boolean reversible() {
        return this == POSTED;
    }
}
