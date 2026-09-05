package com.haowugou.controller.importbatch;

/**
 * 撤销批次的请求体。
 *
 * <p>两个字段都必填，长度上限由应用用例按数据库列宽校验：撤销会改动库存余额，
 * 谁撤的、为什么撤必须留痕。
 *
 * @param reversedBy 撤销操作人
 * @param reversedReason 撤销原因
 */
public record ReverseImportBatchRequest(String reversedBy, String reversedReason) {
}
