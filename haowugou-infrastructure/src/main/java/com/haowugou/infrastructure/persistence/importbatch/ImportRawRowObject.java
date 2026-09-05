package com.haowugou.infrastructure.persistence.importbatch;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code import_raw_row} 表的持久化对象：一条 Excel 原始行及其解析结果。
 *
 * <p>{@code parseStatus} 取 VALID（行本身有效或整批过账）或 INVALID（带 {@code errorMessage}）。
 */
@Getter
@Setter
public class ImportRawRowObject {

    private long rowNumber;
    private String barcode;
    private String rawData;
    private String parseStatus;
    private String errorMessage;
}
