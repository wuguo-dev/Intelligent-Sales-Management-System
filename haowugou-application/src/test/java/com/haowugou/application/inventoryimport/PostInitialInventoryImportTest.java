package com.haowugou.application.inventoryimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.application.inventoryimport.exception.ActiveInitialBatchExistsException;
import com.haowugou.application.inventoryimport.exception.DuplicateImportFileException;
import com.haowugou.application.inventoryimport.exception.ImportWarehouseException;
import com.haowugou.application.inventoryimport.exception.InvalidImportFileException;
import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportBatchRepository;
import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.importbatch.ImportFileParser;
import com.haowugou.domain.importbatch.ImportPosting;
import com.haowugou.domain.importbatch.ImportRowError;
import com.haowugou.domain.importbatch.ParsedImportFile;
import com.haowugou.domain.importbatch.ParsedImportRow;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import com.haowugou.domain.warehouse.WarehouseSummary;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostInitialInventoryImportTest {

    private static final long STORE_ID = 1L;
    private static final long WAREHOUSE_ID = 5L;
    private static final long OTHER_WAREHOUSE_ID = 6L;
    private static final LocalDate DATA_DATE = LocalDate.of(2026, 8, 27);
    private static final byte[] CONTENT = "initial-inventory".getBytes(StandardCharsets.UTF_8);

    private RecordingImportRepository imports;
    private StubFileParser parser;
    private Set<Long> activeStores;
    private Set<String> storeWarehouses;

    @BeforeEach
    void setUp() {
        imports = new RecordingImportRepository();
        parser = new StubFileParser();
        activeStores = new HashSet<>(List.of(STORE_ID));
        storeWarehouses = new HashSet<>(List.of(STORE_ID + ":" + WAREHOUSE_ID));
    }

    @Test
    void postsValidatedRowsSkipsZeroQuantityAndCarriesWarehouse() {
        parser.rows = List.of(
                row(2, "9556155017024", "20", "{\"条码\":\"9556155017024\"}"),
                row(3, "9556155017025", "0", "{\"条码\":\"9556155017025\"}"));
        imports.productIds = Map.of("9556155017024", 10L, "9556155017025", 11L);

        ImportBatchResult result = query()
                .importInventory(STORE_ID, WAREHOUSE_ID, "商品资料.xls", CONTENT);

        assertEquals(9L, result.batchId());
        assertEquals("POSTED", result.status());
        assertEquals(2, result.totalRows());
        assertEquals(2, result.successRows());
        assertEquals(0, result.errorRows());
        assertTrue(result.errors().isEmpty());
        assertEquals(sha256(CONTENT), imports.lastHash);
        ImportPosting posting = imports.posting;
        assertEquals(STORE_ID, posting.storeId());
        assertEquals(WAREHOUSE_ID, posting.warehouseId());
        assertEquals(DATA_DATE, posting.dataDate());
        assertEquals(2, posting.rawRows().size());
        assertEquals(1, posting.postRows().size());
        assertEquals("9556155017024", posting.postRows().getFirst().barcode());
        assertEquals(10L, posting.postRows().getFirst().productId());
        assertEquals(new BigDecimal("20"), posting.postRows().getFirst().quantity());
        assertEquals(List.of("9556155017024", "9556155017025"), imports.askedBarcodes);
    }

    @Test
    void rejectsUnknownBarcodeAndSavesFailedBatchWithoutPosting() {
        parser.rows = List.of(
                row(2, "9556155017024", "20", "{}"),
                row(3, "9999999999999", "5", "{}"));
        imports.productIds = Map.of("9556155017024", 10L);

        ImportBatchResult result = query()
                .importInventory(STORE_ID, null, "商品资料.xls", CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(2, result.totalRows());
        assertEquals(0, result.successRows());
        assertEquals(1, result.errorRows());
        assertEquals(3L, result.errors().getFirst().rowNumber());
        assertTrue(result.errors().getFirst().message().contains("条码不存在"));
        assertNull(imports.posting);
        ImportFailure failure = imports.failure;
        assertEquals(STORE_ID, failure.storeId());
        assertEquals(2, failure.rows().size());
        assertNull(failure.rows().get(0).errorMessage());
        assertTrue(failure.rows().get(1).errorMessage().contains("条码不存在"));
        assertTrue(failure.errorSummary().contains("第3行"));
    }

    @Test
    void rejectsNegativeUnparsableAndOverScaledQuantities() {
        parser.rows = List.of(
                row(2, "A", "-1", "{}"),
                row(3, "B", "abc", "{}"),
                row(4, "C", "0.0005", "{}"),
                row(5, "D", "1", "{}"));
        imports.productIds = Map.of("A", 1L, "B", 2L, "C", 3L, "D", 4L);

        ImportBatchResult result = query()
                .importInventory(STORE_ID, null, "商品资料.xls", CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(3, result.errorRows());
        assertEquals(List.of(2L, 3L, 4L),
                result.errors().stream().map(ImportRowError::rowNumber).toList());
        assertFalse(imports.askedBarcodes.contains("A"));
    }

    @Test
    void rejectsDuplicateAndBlankAndMalformedBarcodes() {
        parser.rows = List.of(
                row(2, "9556155017024", "1", "{}"),
                row(3, "9556155017024", "2", "{}"),
                row(4, " ", "1", "{}"),
                row(5, "9.55E12", "1", "{}"));

        ImportBatchResult result = query()
                .importInventory(STORE_ID, null, "商品资料.xls", CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(3, result.errorRows());
        assertTrue(result.errors().get(0).message().contains("重复"));
        assertTrue(result.errors().get(1).message().contains("条码为空"));
        assertTrue(result.errors().get(2).message().contains("格式非法"));
    }

    @Test
    void rejectsUnknownStoreBeforeAnyFileWork() {
        parser.failWith = new ImportFileFormatException("不应被调用");

        assertThrows(StoreNotFoundException.class,
                () -> query().importInventory(99L, null, "商品资料.xls", CONTENT));
        assertNull(imports.lastHash);
    }

    @Test
    void rejectsDuplicateFileHashBeforeParsing() {
        imports.fileHashExists = true;
        parser.failWith = new ImportFileFormatException("不应被调用");

        DuplicateImportFileException exception = assertThrows(DuplicateImportFileException.class,
                () -> query().importInventory(STORE_ID, null, "商品资料.xls", CONTENT));
        assertTrue(exception.getMessage().contains("商品资料.xls"));
    }

    @Test
    void rejectsExistingActiveInitialBatchBeforeParsing() {
        imports.activeInitialBatchExists = true;
        parser.failWith = new ImportFileFormatException("不应被调用");

        assertThrows(ActiveInitialBatchExistsException.class,
                () -> query().importInventory(STORE_ID, null, "商品资料.xls", CONTENT));
    }

    @Test
    void wrapsParserFormatErrorsAsInvalidFile() {
        parser.failWith = new ImportFileFormatException("表头缺少「条码」");

        InvalidImportFileException exception = assertThrows(InvalidImportFileException.class,
                () -> query().importInventory(STORE_ID, null, "商品资料.xls", CONTENT));
        assertTrue(exception.getMessage().contains("表头缺少"));
        assertNull(imports.posting);
        assertNull(imports.failure);
    }

    @Test
    void rejectsUnsupportedExtensionEmptyContentAndMissingName() {
        parser.failWith = new ImportFileFormatException("不应被调用");
        PostInitialInventoryImport query = query();

        assertThrows(InvalidImportFileException.class,
                () -> query.importInventory(STORE_ID, null, "商品资料.csv", CONTENT));
        assertThrows(InvalidImportFileException.class,
                () -> query.importInventory(STORE_ID, null, "商品资料.xls", new byte[0]));
        assertThrows(InvalidImportFileException.class,
                () -> query.importInventory(STORE_ID, null, null, CONTENT));
        assertThrows(InvalidImportFileException.class,
                () -> query.importInventory(0L, null, "商品资料.xls", CONTENT));
    }

    @Test
    void rejectsEmptyFileAfterParsing() {
        parser.rows = List.of();

        InvalidImportFileException exception = assertThrows(InvalidImportFileException.class,
                () -> query().importInventory(STORE_ID, null, "商品资料.xls", CONTENT));
        assertTrue(exception.getMessage().contains("没有数据行"));
    }

    @Test
    void validatesOptionalWarehouseAgainstTheStore() {
        parser.rows = List.of(row(2, "9556155017024", "1", "{}"));
        imports.productIds = Map.of("9556155017024", 10L);

        ImportBatchResult crossStore = null;
        assertThrows(ImportWarehouseException.class,
                () -> query().importInventory(STORE_ID, OTHER_WAREHOUSE_ID, "商品资料.xls", CONTENT));
        assertThrows(ImportWarehouseException.class,
                () -> query().importInventory(STORE_ID, 0L, "商品资料.xls", CONTENT));
        ImportBatchResult posted = query()
                .importInventory(STORE_ID, WAREHOUSE_ID, "商品资料.xls", CONTENT);
        assertEquals(WAREHOUSE_ID, imports.posting.warehouseId());
        assertEquals("POSTED", posted.status());
    }

    @Test
    void leavesWarehouseNullWhenNotProvided() {
        parser.rows = List.of(row(2, "9556155017024", "1", "{}"));
        imports.productIds = Map.of("9556155017024", 10L);

        query().importInventory(STORE_ID, null, "商品资料.xls", CONTENT);

        assertNull(imports.posting.warehouseId());
    }

    private PostInitialInventoryImport query() {
        return new PostInitialInventoryImport(
                new StoreRepository() {
                    @Override
                    public List<Store> findAllActive() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<Store> findActiveById(long storeId) {
                        return activeStores.contains(storeId)
                                ? Optional.of(new Store(storeId, "S-001", "测试店"))
                                : Optional.empty();
                    }

                    @Override
                    public boolean existsActiveById(long storeId) {
                        return activeStores.contains(storeId);
                    }
                },
                new WarehouseRepository() {
                    @Override
                    public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
                        return List.of();
                    }

                    @Override
                    public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
                        return storeWarehouses.contains(storeId + ":" + warehouseId);
                    }
                },
                imports,
                parser,
                () -> DATA_DATE);
    }

    private ParsedImportRow row(long rowNumber, String barcode, String quantity, String rawData) {
        return new ParsedImportRow(rowNumber, barcode, quantity, rawData);
    }

    private static String sha256(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingImportRepository implements ImportBatchRepository {

        private Map<String, Long> productIds = new LinkedHashMap<>();
        private boolean fileHashExists;
        private boolean activeInitialBatchExists;
        private String lastHash;
        private final List<String> askedBarcodes = new ArrayList<>();
        private ImportPosting posting;
        private ImportFailure failure;

        @Override
        public boolean existsFileHash(long storeId, String fileHash) {
            this.lastHash = fileHash;
            return fileHashExists;
        }

        @Override
        public boolean existsActiveInitialBatch(long storeId) {
            return activeInitialBatchExists;
        }

        @Override
        public Map<String, Long> findProductIdsByBarcodes(List<String> barcodes) {
            askedBarcodes.addAll(barcodes);
            Map<String, Long> found = new LinkedHashMap<>();
            for (String barcode : barcodes) {
                if (productIds.containsKey(barcode)) {
                    found.put(barcode, productIds.get(barcode));
                }
            }
            return found;
        }

        @Override
        public long postBatch(ImportPosting value) {
            this.posting = value;
            return 9L;
        }

        @Override
        public long saveFailedBatch(ImportFailure value) {
            this.failure = value;
            return 3L;
        }
    }

    private static final class StubFileParser implements ImportFileParser {

        private List<ParsedImportRow> rows = List.of();
        private ImportFileFormatException failWith;

        @Override
        public ParsedImportFile parse(byte[] content, String fileName) {
            if (failWith != null) {
                throw failWith;
            }
            return new ParsedImportFile(rows);
        }
    }
}
