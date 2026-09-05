package com.haowugou.domain.importbatch;

/**
 * 批次里一条有问题的原始行（{@code parse_status} 为 INVALID 或 WARNING）。
 *
 * <p>不含 {@code raw_data}：那是审计用的整行 JSON，放进列表响应体积大且对排错无增量价值。
 */
public record ImportBatchProblemRow(
        long rowNumber,
        String barcode,
        String parseStatus,
        String errorMessage) {
}
