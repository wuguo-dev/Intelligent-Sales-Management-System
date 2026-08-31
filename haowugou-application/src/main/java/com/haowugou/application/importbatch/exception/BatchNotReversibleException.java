package com.haowugou.application.importbatch.exception;

import com.haowugou.domain.importbatch.ImportBatchStatus;

/** 批次当前状态不允许撤销：只有 POSTED 可撤销，且不支持撤销的撤销（架构规范 §13）。 */
public final class BatchNotReversibleException extends RuntimeException {

    public BatchNotReversibleException(long batchId, ImportBatchStatus status) {
        super(reason(batchId, status));
    }

    private static String reason(long batchId, ImportBatchStatus status) {
        String detail = switch (status) {
            case REVERSED -> "该批次已撤销，不能重复撤销";
            case FAILED -> "失败批次未产生库存变化，无需撤销";
            case VALIDATING, POSTING -> "该批次尚未过账完成，请稍后再试";
            case POSTED -> "该批次可以撤销";
        };
        return detail + ": batchId=" + batchId + ", status=" + status;
    }
}
