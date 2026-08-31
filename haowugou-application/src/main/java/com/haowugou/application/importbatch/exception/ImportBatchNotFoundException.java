package com.haowugou.application.importbatch.exception;

/** 批次不存在，或存在但不属于该门店——两种情况对外一律 404，不泄露其他门店批次的存在。 */
public final class ImportBatchNotFoundException extends RuntimeException {

    public ImportBatchNotFoundException(long storeId, long batchId) {
        super("导入批次不存在或不属于该门店: storeId=" + storeId + ", batchId=" + batchId);
    }
}
