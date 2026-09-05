package com.haowugou.domain.importbatch;

import java.util.Optional;

/**
 * 批次撤销的持久化边界，两条导入链路共用。
 *
 * <p>撤销与批次类型无关：原流水是 INITIAL_BALANCE 还是 SALE_OUT/SALE_RETURN，都是
 * 「按原流水写符号相反的 REVERSAL + 库存回滚 + 批次翻 REVERSED」。销售事实不动——
 * 视图 {@code v_posted_daily_product_sales} 按 {@code status = 'POSTED'} 过滤，
 * 翻状态即从分析口径消失（架构规范 §12）。
 */
public interface ImportBatchReversalRepository {

    /**
     * 单事务撤销：读原流水、写反向 REVERSAL 流水、回滚库存、批次翻 REVERSED 并记录审计字段。
     *
     * <p>状态检查在事务内完成（{@code UPDATE ... WHERE status = 'POSTED'} 判受影响行数），
     * 并发重复撤销只有一个能成功。
     *
     * @return 撤销结果；批次已不是 POSTED（被并发撤销）时返回空
     */
    Optional<ImportBatchReversalResult> reverse(ImportBatchReversal reversal);
}
