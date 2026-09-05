package com.haowugou.controller.importbatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.haowugou.application.importbatch.ImportBatchQuery;
import com.haowugou.application.importbatch.ReverseImportBatch;
import com.haowugou.controller.ApiExceptionHandler;
import com.haowugou.domain.PageResult;
import com.haowugou.domain.importbatch.ImportBatchDetail;
import com.haowugou.domain.importbatch.ImportBatchListItem;
import com.haowugou.domain.importbatch.ImportBatchProblemRow;
import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import com.haowugou.domain.importbatch.ImportBatchQueryRepository;
import com.haowugou.domain.importbatch.ImportBatchReversal;
import com.haowugou.domain.importbatch.ImportBatchReversalRepository;
import com.haowugou.domain.importbatch.ImportBatchReversalResult;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.importbatch.ImportType;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 从 HTTP 接口验证批次管理 Controller 的公开契约。
 *
 * <p>用真实应用用例 + 内存 Repository 替身，只替换 I/O，不依赖 Spring 上下文与 MySQL。
 */
class ImportBatchControllerTest {

    private static final long STORE_ID = 1L;
    private static final long BATCH_ID = 88L;
    private static final LocalDate DATA_DATE = LocalDate.of(2026, 8, 20);
    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 8, 20, 9, 30);
    private static final LocalDateTime POSTED_AT = LocalDateTime.of(2026, 8, 20, 9, 31);
    private static final LocalDateTime REVERSED_AT = LocalDateTime.of(2026, 8, 30, 14, 5);

    private MockMvc mockMvc;
    private RecordingBatchRepository batches;
    private RecordingReversalRepository reversals;

    @BeforeEach
    void setUp() {
        batches = new RecordingBatchRepository();
        reversals = new RecordingReversalRepository();
        StoreRepository stores = storeRepository();
        ImportBatchController controller = new ImportBatchController(
                new ImportBatchQuery(stores, batches),
                new ReverseImportBatch(stores, batches, reversals));
        JsonMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void listBatchesExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", STORE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "store":{"id":"1","storeCode":"S-001","storeName":"城南店"},
                          "items":[{
                            "batchId":"88",
                            "importType":"DAILY_SALES",
                            "status":"POSTED",
                            "dataDate":"2026-08-20",
                            "fileName":"销售汇总.xls",
                            "totalRows":120,
                            "successRows":120,
                            "errorRows":0,
                            "importedAt":"2026-08-20T09:30:00",
                            "postedAt":"2026-08-20T09:31:00",
                            "reversedAt":null,
                            "reversible":true
                          }],
                          "page":0,
                          "size":20,
                          "totalElements":1,
                          "totalPages":1
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void listBatchesForwardsAllFiltersWithinTheStoreScope() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", STORE_ID)
                        .param("importType", "DAILY_SALES")
                        .param("status", "REVERSED")
                        .param("dataDateFrom", "2026-08-01")
                        .param("dataDateTo", "2026-08-31")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk());

        assertEquals(new ImportBatchQueryCriteria(
                STORE_ID,
                ImportType.DAILY_SALES,
                ImportBatchStatus.REVERSED,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                2,
                10), batches.criteria);
    }

    @Test
    void batchDetailExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches/{batchId}", STORE_ID, BATCH_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "store":{"id":"1","storeCode":"S-001","storeName":"城南店"},
                          "batch":{
                            "batchId":"88",
                            "importType":"DAILY_SALES",
                            "status":"POSTED",
                            "dataDate":"2026-08-20",
                            "fileName":"销售汇总.xls",
                            "fileHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "totalRows":120,
                            "successRows":120,
                            "errorRows":0,
                            "errorMessage":null,
                            "operatorName":"张三",
                            "importedAt":"2026-08-20T09:30:00",
                            "postedAt":"2026-08-20T09:31:00",
                            "reversedAt":null,
                            "reversedBy":null,
                            "reversedReason":null,
                            "reversible":true
                          },
                          "problemRows":{
                            "items":[{
                              "rowNumber":7,
                              "barcode":"6901234567890",
                              "parseStatus":"INVALID",
                              "errorMessage":"库存数量不是数字"
                            }],
                            "page":0,
                            "size":20,
                            "totalElements":1,
                            "totalPages":1
                          }
                        }
                        """, JsonCompareMode.STRICT));
    }

    /** 分页参数只作用于问题行，批次元信息不受影响。 */
    @Test
    void batchDetailPagesProblemRowsOnly() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches/{batchId}", STORE_ID, BATCH_ID)
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.batchId").value(BATCH_ID))
                .andExpect(jsonPath("$.problemRows.page").value(1))
                .andExpect(jsonPath("$.problemRows.size").value(5));

        assertEquals(1, batches.problemRowPage);
        assertEquals(5, batches.problemRowSize);
    }

    @Test
    void reverseBatchExposesStableJsonContract() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/import-batches/{batchId}/reverse", STORE_ID, BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversedBy":"李四","reversedReason":"业务日期填错，需要重传"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "store":{"id":"1","storeCode":"S-001","storeName":"城南店"},
                          "batchId":"88",
                          "importType":"DAILY_SALES",
                          "dataDate":"2026-08-20",
                          "fileName":"销售汇总.xls",
                          "reversedMovements":3,
                          "restoredProducts":2,
                          "reversedAt":"2026-08-30T14:05:00",
                          "reversedBy":"李四",
                          "reversedReason":"业务日期填错，需要重传"
                        }
                        """, JsonCompareMode.STRICT));

        assertEquals(STORE_ID, reversals.received.storeId());
        assertEquals(BATCH_ID, reversals.received.batchId());
        assertEquals("李四", reversals.received.reversedBy());
    }

    @Test
    void reverseBatchWithoutOperatorOrReasonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/import-batches/{batchId}/reverse", STORE_ID, BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversedReason":"填错了"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("撤销操作人不能为空"));

        mockMvc.perform(post("/api/stores/{storeId}/import-batches/{batchId}/reverse", STORE_ID, BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversedBy":"李四"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("撤销原因不能为空"));
    }

    @Test
    void reverseAlreadyReversedBatchReturnsConflict() throws Exception {
        batches.status = ImportBatchStatus.REVERSED;

        mockMvc.perform(post("/api/stores/{storeId}/import-batches/{batchId}/reverse", STORE_ID, BATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversedBy":"李四","reversedReason":"重复撤销"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("批次不可撤销"))
                .andExpect(jsonPath("$.detail").value(
                        "该批次已撤销，不能重复撤销: batchId=88, status=REVERSED"));
    }

    @Test
    void batchOfAnotherStoreReturnsNotFound() throws Exception {
        batches.detailPresent = false;

        mockMvc.perform(get("/api/stores/{storeId}/import-batches/{batchId}", STORE_ID, 999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("导入批次不存在"))
                .andExpect(jsonPath("$.detail").value(
                        "导入批次不存在或不属于该门店: storeId=1, batchId=999"));

        mockMvc.perform(post("/api/stores/{storeId}/import-batches/{batchId}/reverse", STORE_ID, 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversedBy":"李四","reversedReason":"试探别人的批次"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownStoreReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("门店不存在或未启用"))
                .andExpect(jsonPath("$.detail").value("门店不存在或未启用: 99"));
    }

    @Test
    void invalidPagingReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", STORE_ID)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("每页数量必须在1到100之间"));
    }

    @Test
    void invalidDateRangeReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", STORE_ID)
                        .param("dataDateFrom", "2026-08-31")
                        .param("dataDateTo", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("开始日期不能晚于结束日期"));
    }

    @Test
    void unknownImportTypeReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", STORE_ID)
                        .param("importType", "MONTHLY_SALES"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("请求参数格式错误: importType"));
    }

    private StoreRepository storeRepository() {
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

    /** 批次查询端口替身：记录收到的参数，按开关切换状态与是否存在。 */
    private static final class RecordingBatchRepository implements ImportBatchQueryRepository {

        private ImportBatchQueryCriteria criteria;
        private int problemRowPage = -1;
        private int problemRowSize = -1;
        private boolean detailPresent = true;
        private ImportBatchStatus status = ImportBatchStatus.POSTED;

        @Override
        public PageResult<ImportBatchListItem> findPage(ImportBatchQueryCriteria criteria) {
            this.criteria = criteria;
            return new PageResult<>(
                    List.of(new ImportBatchListItem(
                            BATCH_ID, STORE_ID, ImportType.DAILY_SALES, status, DATA_DATE,
                            "销售汇总.xls", 120, 120, 0, IMPORTED_AT, POSTED_AT, null)),
                    criteria.page(), criteria.size(), 1, 1);
        }

        @Override
        public Optional<ImportBatchDetail> findDetail(long storeId, long batchId) {
            if (!detailPresent) {
                return Optional.empty();
            }
            return Optional.of(new ImportBatchDetail(
                    BATCH_ID, STORE_ID, ImportType.DAILY_SALES, status, DATA_DATE,
                    "销售汇总.xls", "a".repeat(64), 120, 120, 0, null, "张三",
                    IMPORTED_AT, POSTED_AT, null, null, null));
        }

        @Override
        public PageResult<ImportBatchProblemRow> findProblemRows(
                long storeId, long batchId, int page, int size) {
            this.problemRowPage = page;
            this.problemRowSize = size;
            return new PageResult<>(
                    List.of(new ImportBatchProblemRow(7L, "6901234567890", "INVALID", "库存数量不是数字")),
                    page, size, 1, 1);
        }
    }

    /** 撤销端口替身：记录命令并返回固定的撤销规模。 */
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
