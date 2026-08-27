package com.haowugou.domain.importbatch;

/**
 * 解析器产出的一条原始数据行。
 *
 * @param rowNumber Excel 实际行号（表头为第 1 行，数据从第 2 行起）
 * @param barcode   条码列文本（已去除首尾空白）
 * @param quantity  库存数量列文本（已去除首尾空白，业务解析由应用层完成）
 * @param rawData   整行所有列的文本 JSON，用于审计
 */
public record ParsedImportRow(
        long rowNumber,
        String barcode,
        String quantity,
        String rawData) {
}
