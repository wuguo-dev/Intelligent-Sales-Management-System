package com.haowugou.application.salesimport;

/**
 * 每日销售导入的请求级错误：门店ID或业务日期非法、扩展名不支持、文件为空、无法解析、
 * 表头缺失或没有数据行。
 *
 * <p>对应 HTTP 400，不落批次。
 */
public final class InvalidSalesImportException extends RuntimeException {

    public InvalidSalesImportException(String message) {
        super(message);
    }
}
