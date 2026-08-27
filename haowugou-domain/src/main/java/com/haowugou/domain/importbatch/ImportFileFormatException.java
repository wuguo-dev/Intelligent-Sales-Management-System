package com.haowugou.domain.importbatch;

/**
 * 导入文件格式错误：无法解析、表头缺失或文件中没有数据行。
 *
 * <p>应用层将其转换为面向客户端的 {@code InvalidImportFileException}（HTTP 400）。
 */
public final class ImportFileFormatException extends RuntimeException {

    public ImportFileFormatException(String message) {
        super(message);
    }
}
