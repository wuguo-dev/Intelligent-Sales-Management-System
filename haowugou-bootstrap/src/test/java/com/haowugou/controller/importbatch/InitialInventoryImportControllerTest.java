package com.haowugou.controller.importbatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.haowugou.application.inventoryimport.PostInitialInventoryImport;
import com.haowugou.controller.ApiExceptionHandler;
import com.haowugou.domain.importbatch.ImportBatchRepository;
import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.importbatch.ImportFileParser;
import com.haowugou.domain.importbatch.ImportPosting;
import com.haowugou.domain.importbatch.ParsedImportFile;
import com.haowugou.domain.importbatch.ParsedImportRow;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import com.haowugou.domain.warehouse.WarehouseSummary;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 从 HTTP 接口验证初始库存导入 Controller 的公开契约。
 *
 * <p>测试使用真实导入用例和内存替身（Repository / 解析器），只替换 I/O Adapter，
 * 不依赖 MySQL 或 EasyExcel 内部实现。
 */
class InitialInventoryImportControllerTest {

    private static final long STORE_ID = 1L;
    private static final long WAREHOUSE_ID = 5L;
    private static final LocalDate DATA_DATE = LocalDate.of(2026, 8, 27);
    private static final String FILE_NAME = "商品资料1.xls";

    private MockMvc mockMvc;
    private RecordingImportRepository imports;
    private List<ParsedImportRow> parsedRows;

    @BeforeEach
    void setUp() {
        imports = new RecordingImportRepository();
        imports.productIds.put("9556155017024", 10L);
        imports.productIds.put("0000001234567", 11L);
        parsedRows = List.of(
                new ParsedImportRow(2L, "9556155017024", "20",
                        "{\"条码\":\"9556155017024\",\"库存数量\":\"20\"}"),
                new ParsedImportRow(3L, "0000001234567", "0.5",
                        "{\"条码\":\"0000001234567\",\"库存数量\":\"0.5\"}"));

        PostInitialInventoryImport useCase = new PostInitialInventoryImport(
                storeRepository(), warehouseRepository(), imports, fileParser(), () -> DATA_DATE);
        JsonMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new InitialInventoryImportController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void postsValidFileReturnsPostedContract() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .file(validFile()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "batchId":42,
                          "status":"POSTED",
                          "totalRows":2,
                          "successRows":2,
                          "errorRows":0,
                          "errors":[]
                        }
                        """, JsonCompareMode.STRICT));

        assertEquals(FILE_NAME, imports.posted.fileName());
        assertEquals(DATA_DATE, imports.posted.dataDate());
        assertNull(imports.posted.warehouseId());
        assertEquals(2, imports.posted.rawRows().size());
        assertEquals(2, imports.posted.postRows().size());
    }

    @Test
    void optionalWarehouseIdIsCarriedToPosting() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .file(validFile())
                        .param("warehouseId", String.valueOf(WAREHOUSE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        assertEquals(WAREHOUSE_ID, imports.posted.warehouseId());
    }

    @Test
    void unknownBarcodeReturnsFailedContract() throws Exception {
        parsedRows = List.of(
                new ParsedImportRow(2L, "UNKNOWN", "1", "{\"条码\":\"UNKNOWN\",\"库存数量\":\"1\"}"));

        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .file(validFile()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "batchId":43,
                          "status":"FAILED",
                          "totalRows":1,
                          "successRows":0,
                          "errorRows":1,
                          "errors":[
                            {"rowNumber":2,"barcode":"UNKNOWN","message":"条码不存在: UNKNOWN"}
                          ]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void missingFilePartReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"));
    }

    @Test
    void nonMultipartRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .param("warehouseId", String.valueOf(WAREHOUSE_ID))
                        .param("file", FILE_NAME))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail")
                        .value("请求不是 multipart/form-data 表单，请以 form-data 方式上传 file 文件参数"));
    }

    @Test
    void unsupportedExtensionReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .file(new MockMultipartFile(
                                "file", "数据.txt", "text/plain", new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("仅支持 .xls 或 .xlsx 文件: 数据.txt"));
    }

    @Test
    void unknownStoreReturnsNotFound() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", 99L)
                        .file(validFile()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("门店不存在或未启用"))
                .andExpect(jsonPath("$.detail").value("门店不存在或未启用: 99"));
    }

    @Test
    void duplicateFileReturnsConflict() throws Exception {
        imports.fileHashExists = true;

        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .file(validFile()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("导入冲突"))
                .andExpect(jsonPath("$.detail").value("该文件已导入过: " + FILE_NAME));
    }

    @Test
    void existingActiveBatchReturnsConflict() throws Exception {
        imports.activeInitialBatch = true;

        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .file(validFile()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("导入冲突"))
                .andExpect(jsonPath("$.detail").value("门店已有有效初始库存批次: " + STORE_ID));
    }

    @Test
    void warehouseFromAnotherStoreReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/inventory/import", STORE_ID)
                        .file(validFile())
                        .param("warehouseId", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("仓库不属于该门店: storeId=1, warehouseId=9"));
    }

    private MockMultipartFile validFile() {
        return new MockMultipartFile(
                "file", FILE_NAME, "application/vnd.ms-excel", new byte[]{1, 2, 3});
    }

    private ImportFileParser fileParser() {
        return (content, fileName) -> new ParsedImportFile(parsedRows);
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

    private WarehouseRepository warehouseRepository() {
        return new WarehouseRepository() {
            @Override
            public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
                return List.of();
            }

            @Override
            public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
                return storeId == STORE_ID && warehouseId == WAREHOUSE_ID;
            }
        };
    }

    private static final class RecordingImportRepository implements ImportBatchRepository {

        private boolean fileHashExists;
        private boolean activeInitialBatch;
        private final Map<String, Long> productIds = new LinkedHashMap<>();
        private ImportPosting posted;
        private ImportFailure failed;

        @Override
        public boolean existsFileHash(long storeId, String fileHash) {
            return fileHashExists;
        }

        @Override
        public boolean existsActiveInitialBatch(long storeId) {
            return activeInitialBatch;
        }

        @Override
        public Map<String, Long> findProductIdsByBarcodes(List<String> barcodes) {
            return productIds;
        }

        @Override
        public long postBatch(ImportPosting posting) {
            this.posted = posting;
            return 42L;
        }

        @Override
        public long saveFailedBatch(ImportFailure failure) {
            this.failed = failure;
            return 43L;
        }
    }
}
