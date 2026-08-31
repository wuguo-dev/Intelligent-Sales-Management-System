package com.haowugou.application.importbatch.exception;

/** 导入批次查询或撤销的入参不合法。 */
public final class InvalidImportBatchQueryException extends RuntimeException {

    public InvalidImportBatchQueryException(String message) {
        super(message);
    }
}
