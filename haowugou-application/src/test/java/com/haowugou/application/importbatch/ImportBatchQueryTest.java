package com.haowugou.application.importbatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.application.importbatch.exception.ImportBatchNotFoundException;
import com.haowugou.application.importbatch.exception.InvalidImportBatchQueryException;
import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.importbatch.ImportType;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportBatchQueryTest {

    private static final long STORE_ID = 1L;
    private static final long BATCH_ID = 88L;
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Test
    void forwardsAllFiltersWithinTheValidatedStoreScope() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        ImportBatchQuery query = new ImportBatchQuery(activeStores(), batches);
        ImportBatchQueryCriteria criteria = new ImportBatchQueryCriteria(
                STORE_ID, ImportType.DAILY_SALES, ImportBatchStatus.POSTED, FROM, TO, 2, 10);

        ImportBatchPageResult result = query.listBatches(criteria);

        assertEquals(STORE_ID, result.store().id());
        assertSame(criteria, batches.criteria);
        assertEquals(2, result.batches().page());
        assertEquals(BATCH_ID, result.batches().items().getFirst().batchId());
    }

    @Test
    void returnsDetailWithProblemRowsForTheRequestedStore() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        ImportBatchQuery query = new ImportBatchQuery(activeStores(), batches);

        ImportBatchDetailResult result = query.findBatch(STORE_ID, BATCH_ID, 1, 5);

        assertEquals(STORE_ID, result.store().id());
        assertEquals(BATCH_ID, result.batch().batchId());
        assertTrue(result.batch().reversible());
        assertEquals(STORE_ID, batches.detailStoreId);
        assertEquals(BATCH_ID, batches.problemRowBatchId);
        assertEquals(1, result.problemRows().page());
        assertEquals(7L, result.problemRows().items().getFirst().rowNumber());
    }

    @Test
    void reportsBatchOfAnotherStoreAsNotFound() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        batches.detailPresent = false;
        ImportBatchQuery query = new ImportBatchQuery(activeStores(), batches);

        assertThrows(ImportBatchNotFoundException.class, () -> query.findBatch(STORE_ID, 999L, 0, 20));
    }

    @Test
    void rejectsInvalidPagingAndDateRangeBeforeReadingRepositories() {
        ImportBatchQuery query = new ImportBatchQuery(failingStores(), failingBatches());

        assertThrows(InvalidImportBatchQueryException.class, () -> query.listBatches(null));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.listBatches(criteria(0L, 0, 20)));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.listBatches(criteria(STORE_ID, -1, 20)));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.listBatches(criteria(STORE_ID, 0, 0)));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.listBatches(criteria(STORE_ID, 0, 101)));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.listBatches(new ImportBatchQueryCriteria(
                STORE_ID, null, null, TO, FROM, 0, 20)));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.findBatch(0L, BATCH_ID, 0, 20));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.findBatch(STORE_ID, 0L, 0, 20));
        assertThrows(InvalidImportBatchQueryException.class, () -> query.findBatch(STORE_ID, BATCH_ID, 0, 0));
    }

    /** 上下界可以单独给：只想看某天之后的批次不必再编一个上界。 */
    @Test
    void acceptsOpenEndedDateRange() {
        ImportBatchRepositoryStub batches = new ImportBatchRepositoryStub();
        ImportBatchQuery query = new ImportBatchQuery(activeStores(), batches);

        query.listBatches(new ImportBatchQueryCriteria(STORE_ID, null, null, FROM, null, 0, 20));
        query.listBatches(new ImportBatchQueryCriteria(STORE_ID, null, null, null, TO, 0, 20));

        assertEquals(TO, batches.criteria.dataDateTo());
    }

    @Test
    void rejectsUnknownStoreBeforeBatchQueries() {
        ImportBatchQuery query = new ImportBatchQuery(activeStores(), failingBatches());

        assertThrows(StoreNotFoundException.class, () -> query.listBatches(criteria(99L, 0, 20)));
        assertThrows(StoreNotFoundException.class, () -> query.findBatch(99L, BATCH_ID, 0, 20));
    }

    private ImportBatchQueryCriteria criteria(long storeId, int page, int size) {
        return new ImportBatchQueryCriteria(storeId, null, null, null, null, page, size);
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
}
