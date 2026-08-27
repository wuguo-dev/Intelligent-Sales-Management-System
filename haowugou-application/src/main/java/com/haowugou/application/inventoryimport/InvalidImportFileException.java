package com.haowugou.application.inventoryimport;

/**
 * 导入文件级错误：扩展名不支持、文件为空、无法解析、表头缺失或没有数据行。
 *
 * <p>对应 HTTP 400，不落批次。
 */
public final class InvalidImportFileException extends RuntimeException {

    public InvalidImportFileException(String message) {
        super(message);
    }
}
