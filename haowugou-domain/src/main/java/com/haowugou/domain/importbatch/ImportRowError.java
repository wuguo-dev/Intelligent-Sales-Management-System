package com.haowugou.domain.importbatch;

/** 行级校验错误，用于失败批次的错误摘要与客户端响应。 */
public record ImportRowError(
        long rowNumber,
        String barcode,
        String message) {
}
