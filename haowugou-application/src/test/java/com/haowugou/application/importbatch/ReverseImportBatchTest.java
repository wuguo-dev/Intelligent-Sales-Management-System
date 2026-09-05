package com.haowugou.application.importbatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.application.importbatch.exception.BatchNotReversibleException;
import com.haowugou.application.importbatch.exception.ImportBatchNotFoundException;
import com.haowugou.application.importbatch.exception.InvalidImportBatchQueryException;
import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportBatchReversal;
import com.haowugou.domain.importbatch.ImportBatchReversalRepository;
import com.haowugou.domain.importbatch.ImportBatchReversalResult;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.importbatch.ImportType;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReverseImportBatchTest {

    private static final long STORE_ID = 1L;
    private static final long BATCH_ID = 88L;
    private static final String OPERATOR = "李四";
    private static final String REASON = "业务日期填错，需要重传";
    private static final LocalDateTime REVERSED_AT = LocalDateTime.of(2026, 8, 30, 14, 5);

    @Test
    void reversesPostedBatchAndReportsWhatWasRolledBack() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        RecordingReversalRepository reversals = new RecordingReversalRepository();
        ReverseImportBatch reverse = new ReverseImportBatch(activeStores(), batches, reversals);

        ReverseImportBatchResult result = reverse.reverse(STORE_ID, BATCH_ID, OPERATOR, REASON);

        assertEquals(STORE_ID, result.store().id());
        assertEquals(BATCH_ID, result.batchId());
        assertEquals(ImportType.DAILY_SALES, result.importType());
        assertEquals("销售汇总.xls", result.fileName());
        assertEquals(3, result.reversedMovements());
        assertEquals(2, result.restoredProducts());
        assertEquals(REVERSED_AT, result.reversedAt());
        assertEquals(OPERATOR, result.reversedBy());
        assertEquals(REASON, result.reversedReason());
        assertEquals(STORE_ID, reversals.received.storeId());
        assertEquals(BATCH_ID, reversals.received.batchId());
    }

    /** 操作人与原因两头留白，落库前先 strip：数据库列宽按有效字符算。 */
    @Test
    void trimsOperatorAndReasonBeforeHandingToThePort() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        RecordingReversalRepository reversals = new RecordingReversalRepository();
        ReverseImportBatch reverse = new ReverseImportBatch(activeStores(), batches, reversals);

        ReverseImportBatchResult result = reverse.reverse(STORE_ID, BATCH_ID, "  李四  ", "  填错了  ");

        assertEquals(OPERATOR, reversals.received.reversedBy());
        assertEquals("填错了", reversals.received.reversedReason());
        assertEquals(OPERATOR, result.reversedBy());
    }

    @Test
    void rejectsMissingOperatorOrReasonBeforeTouchingRepositories() {
        ReverseImportBatch reverse = new ReverseImportBatch(
                failingStores(), failingBatches(), failingReversals());

        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(0L, BATCH_ID, OPERATOR, REASON));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(STORE_ID, 0L, OPERATOR, REASON));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(STORE_ID, BATCH_ID, null, REASON));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(STORE_ID, BATCH_ID, "   ", REASON));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(STORE_ID, BATCH_ID, OPERATOR, null));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(STORE_ID, BATCH_ID, OPERATOR, "  "));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(STORE_ID, BATCH_ID, "名".repeat(101), REASON));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> reverse.reverse(STORE_ID, BATCH_ID, OPERATOR, "因".repeat(501)));
    }

    @Test
    void rejectsUnknownStoreBeforeReadingTheBatch() {
        ReverseImportBatch reverse = new ReverseImportBatch(
                activeStores(), failingBatches(), failingReversals());

        assertThrows(StoreNotFoundException.class, () -> reverse.reverse(99L, BATCH_ID, OPERATOR, REASON));
    }

    @Test
    void reportsBatchOfAnotherStoreAsNotFoundWithoutReversing() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        batches.detailPresent = false;
        ReverseImportBatch reverse = new ReverseImportBatch(activeStores(), batches, failingReversals());

        assertThrows(ImportBatchNotFoundException.class,
                () -> reverse.reverse(STORE_ID, 999L, OPERATOR, REASON));
    }

    /** 只有 POSTED 可撤销：其余状态在调用端口之前就拒掉。 */
    @Test
    void rejectsEveryStatusOtherThanPosted() {
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            if (status == ImportBatchStatus.POSTED) {
                continue;
            }
            ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
            batches.status = status;
            ReverseImportBatch reverse = new ReverseImportBatch(
                    activeStores(), batches, failingReversals());

            BatchNotReversibleException exception = assertThrows(BatchNotReversibleException.class,
                    () -> reverse.reverse(STORE_ID, BATCH_ID, OPERATOR, REASON));
            assertTrue(exception.getMessage().contains(status.name()));
        }
    }

    /**
     * 端口返回空表示批次已不是 POSTED——读到 POSTED 之后另一个请求先撤销了。
     * 这一步是并发下的真正判据，必须同样映射成不可撤销。
     */
    @Test
    void mapsConcurrentReversalToNotReversible() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        ImportBatchReversalRepository reversals = reversal -> Optional.empty();
        ReverseImportBatch reverse = new ReverseImportBatch(activeStores(), batches, reversals);

        BatchNotReversibleException exception = assertThrows(BatchNotReversibleException.class,
                () -> reverse.reverse(STORE_ID, BATCH_ID, OPERATOR, REASON));
        assertTrue(exception.getMessage().contains("已撤销"));
    }

    private StoreRepository activeStores() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                return List.of(new Store(STORE_ID, "S-001", "城南店"));
            }

            @Override
            public boolean existsActiveById(long storeId) {
                return storeId == STORE_ID;
            }
        };
    }

    private StoreRepository failingStores() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                throw new AssertionError("非法参数不应查询门店");
            }

            @Override
            public boolean existsActiveById(long storeId) {
                throw new AssertionError("非法参数不应查询门店");
            }
        };
    }

    private ImportBatchRepositoryStub failingBatches() {
        ImportBatchRepositoryStub stub = new ImportBatchRepositoryStub();
        stub.failOnAccess = true;
        return stub;
    }

    private ImportBatchReversalRepository failingReversals() {
        return reversal -> {
            throw new AssertionError("校验未通过时不应撤销批次");
        };
    }

    /** 记录端口收到的撤销命令。 */
    private static final class RecordingReversalRepository implements ImportBatchReversalRepository {

        private ImportBatchReversal received;

        @Override
        public Optional<ImportBatchReversalResult> reverse(ImportBatchReversal reversal) {
            this.received = reversal;
            return Optional.of(new ImportBatchReversalResult(
                    reversal.batchId(), ImportType.DAILY_SALES, 3, 2, REVERSED_AT));
        }
    }
}
