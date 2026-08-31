package com.haowugou.application.importbatch;

import com.haowugou.application.importbatch.exception.BatchNotReversibleException;
import com.haowugou.application.importbatch.exception.ImportBatchNotFoundException;
import com.haowugou.application.importbatch.exception.InvalidImportBatchQueryException;
import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportBatchDetail;
import com.haowugou.domain.importbatch.ImportBatchQueryRepository;
import com.haowugou.domain.importbatch.ImportBatchReversal;
import com.haowugou.domain.importbatch.ImportBatchReversalRepository;
import com.haowugou.domain.importbatch.ImportBatchReversalResult;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.util.Objects;

/**
 * 撤销导入批次的应用用例：POSTED → REVERSED（架构规范 §13）。
 *
 * <p>本用例只做校验与编排，反向量与余额链由 {@link ImportBatchReversalRepository} 在单事务内
 * 从原流水推出——撤销量存在于数据库而非请求里，拆成「先读原流水再回头写」会让读写之间的
 * 并发导入把余额链算错。
 *
 * <p>撤销初始库存批次会让库存变负（后续销售批次已在其基础上扣减），这是规范 §12
 * 「负库存允许存在」下的可接受行为，本用例不拦。
 */
public final class ReverseImportBatch {

    /** 对应 {@code import_batch.reversed_by} 的列长度。 */
    static final int MAX_REVERSED_BY_LENGTH = 100;

    /** 对应 {@code import_batch.reversed_reason} 的列长度。 */
    static final int MAX_REASON_LENGTH = 500;

    private final StoreRepository storeRepository;
    private final ImportBatchQueryRepository batchRepository;
    private final ImportBatchReversalRepository reversalRepository;

    public ReverseImportBatch(
            StoreRepository storeRepository,
            ImportBatchQueryRepository batchRepository,
            ImportBatchReversalRepository reversalRepository) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.batchRepository = Objects.requireNonNull(batchRepository);
        this.reversalRepository = Objects.requireNonNull(reversalRepository);
    }

    public ReverseImportBatchResult reverse(
            long storeId,
            long batchId,
            String reversedBy,
            String reversedReason) {
        requirePositive(storeId, "门店ID");
        requirePositive(batchId, "批次ID");
        String operator = requireText(reversedBy, "撤销操作人", MAX_REVERSED_BY_LENGTH);
        String reason = requireText(reversedReason, "撤销原因", MAX_REASON_LENGTH);

        Store store = storeRepository.findActiveById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        ImportBatchDetail batch = batchRepository.findDetail(storeId, batchId)
                .orElseThrow(() -> new ImportBatchNotFoundException(storeId, batchId));
        if (!batch.reversible()) {
            throw new BatchNotReversibleException(batchId, batch.status());
        }

        // 端口内的状态检查是并发下的真正判据：这里读到 POSTED 之后仍可能被另一个请求先撤销。
        ImportBatchReversalResult reversal = reversalRepository
                .reverse(new ImportBatchReversal(storeId, batchId, operator, reason))
                .orElseThrow(() -> new BatchNotReversibleException(batchId, ImportBatchStatus.REVERSED));

        return new ReverseImportBatchResult(
                store,
                batch.batchId(),
                batch.importType(),
                batch.dataDate(),
                batch.fileName(),
                reversal.reversedMovements(),
                reversal.restoredProducts(),
                reversal.reversedAt(),
                operator,
                reason);
    }

    private void requirePositive(long value, String label) {
        if (value <= 0) {
            throw new InvalidImportBatchQueryException(label + "必须大于0");
        }
    }

    private String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidImportBatchQueryException(label + "不能为空");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new InvalidImportBatchQueryException(label + "不能超过" + maxLength + "个字符");
        }
        return trimmed;
    }
}
