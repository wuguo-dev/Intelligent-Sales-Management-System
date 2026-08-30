package com.haowugou.application.inventoryimport.exception;

/** 同一文件（同门店、同导入类型、同 SHA-256）已导入过。对应 HTTP 409。 */
public final class DuplicateImportFileException extends RuntimeException {

    public DuplicateImportFileException(String fileName) {
        super("该文件已导入过: " + fileName);
    }
}
