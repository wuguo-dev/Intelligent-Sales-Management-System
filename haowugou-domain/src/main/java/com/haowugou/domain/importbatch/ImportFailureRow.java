package com.haowugou.domain.importbatch;

/**
 * 失败批次的一条原始行。
 *
 * @param errorMessage 行级错误；为 null 表示该行本身有效（因整批拒而未过账）
 */
public record ImportFailureRow(
        long rowNumber,
        String barcode,
        String rawData,
        String errorMessage) {
}
