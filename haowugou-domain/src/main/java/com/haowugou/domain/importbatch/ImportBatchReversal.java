package com.haowugou.domain.importbatch;

/**
 * 撤销一个批次的完整指令。
 *
 * <p>撤销量由数据库里该批次的原流水决定，调用方不传数量——反向量与余额链在实现内单事务算出，
 * 拆成「先读原流水再回头写」会让读写之间的并发导入把余额链算错（架构规范 §12）。
 *
 * @param reversedBy     撤销操作人，必填（架构规范 §18）
 * @param reversedReason 撤销原因，必填
 */
public record ImportBatchReversal(
        long storeId,
        long batchId,
        String reversedBy,
        String reversedReason) {
}
