package com.haowugou.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.application.inventoryimport.PostInitialInventoryImport;
import com.haowugou.application.inventoryimport.exception.ActiveInitialBatchExistsException;
import com.haowugou.application.inventoryimport.exception.DuplicateImportFileException;
import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import com.haowugou.domain.warehouse.WarehouseSummary;
import com.haowugou.infrastructure.fileimport.PosProductExcelFileParser;
import com.haowugou.infrastructure.persistence.importbatch.ImportBatchMapper;
import com.haowugou.infrastructure.persistence.importbatch.MybatisImportBatchRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 初始库存导入的 MySQL 全链路集成测试：真实解析器 + 真实用例 + 真实 MyBatis Adapter，
 * 门店与仓库用 JDBC 直查替身。夹具使用高位 ID（8.2e18+）与 {@code IT-} 前缀，
 * 结束后通过 JDBC Connection 整体回滚，不留测试数据。
 */
class InitialInventoryImportIntegrationTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2026, 8, 27);
    private static final String FILE_NAME = "IT-商品资料.xls";

    private SqlSession session;
    private Connection connection;
    private PostInitialInventoryImport useCase;
    private TestIds ids;

    @BeforeEach
    void setUp() throws Exception {
        String password = System.getenv("HAOWUGOU_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "设置 HAOWUGOU_DB_PASSWORD 后执行真实 MySQL 集成测试");
        String url = System.getenv().getOrDefault(
                "HAOWUGOU_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/haowugou?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("HAOWUGOU_DB_USERNAME", "root");

        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, username, password);
        Configuration configuration = new Configuration(new Environment(
                "mysql-integration-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Resources.getResourceAsStream("mapper/ImportBatchMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/ImportBatchMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(false);
        connection = session.getConnection();
        ids = TestIds.create();
        insertFixtures(connection, ids);

        MybatisImportBatchRepository repository = new MybatisImportBatchRepository(
                session.getMapper(ImportBatchMapper.class));
        useCase = new PostInitialInventoryImport(
                storeRepository(),
                warehouseRepository(),
                repository,
                new PosProductExcelFileParser(new ObjectMapper()),
                () -> DATA_DATE);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            try {
                connection.rollback();
            } catch (SQLException exception) {
                throw new IllegalStateException("回滚 MySQL 集成测试数据失败", exception);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void postsBatchRawRowsInventoryAndMovementsAtomically() throws Exception {
        byte[] content = excel(
                row(ids.productA(), "2.500"),
                row(ids.productB(), "3"),
                row(ids.productC(), "0"));

        ImportBatchResult result = useCase.importInventory(
                ids.store(), ids.warehouseTwo(), FILE_NAME, content);

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(3, result.totalRows());
        assertEquals(3, result.successRows());
        assertEquals(0, result.errorRows());

        BatchRow batch = batch(result.batchId());
        assertEquals("INITIAL_INVENTORY", batch.importType());
        assertEquals("POSTED", batch.status());
        assertEquals(DATA_DATE, batch.dataDate());
        assertEquals(sha256(content), batch.fileHash());
        assertTrue(batch.postedAtNotNull());

        assertEquals(List.of(
                new RawRow(2, "6900" + ids.productA(), "VALID", null),
                new RawRow(3, "6900" + ids.productB(), "VALID", null),
                new RawRow(4, "6900" + ids.productC(), "VALID", null)),
                rawRows(result.batchId()));
        String raw = rawData(result.batchId(), 2);
        assertTrue(raw.contains("条码"), "原始行 JSON 应含条码列: " + raw);
        assertTrue(raw.contains("6900" + ids.productA()), "原始行 JSON 应含条码值: " + raw);

        Inventory inventoryA = inventory(ids.store(), ids.productA());
        assertEquals(new BigDecimal("7.500"), inventoryA.quantity());
        assertEquals(1, inventoryA.version());
        assertEquals(ids.warehouseOne(), inventoryA.warehouseId()); // 冲突行不覆盖仓库分配
        Inventory inventoryB = inventory(ids.store(), ids.productB());
        assertEquals(new BigDecimal("3.000"), inventoryB.quantity());
        assertEquals(ids.warehouseTwo(), inventoryB.warehouseId()); // 新行落导入仓库
        assertNull(inventory(ids.store(), ids.productC())); // 零数量行不建库存

        assertEquals(List.of(
                new Movement(ids.productA(), "INITIAL_BALANCE", DATA_DATE,
                        new BigDecimal("2.500"), new BigDecimal("5.000"), new BigDecimal("7.500")),
                new Movement(ids.productB(), "INITIAL_BALANCE", DATA_DATE,
                        new BigDecimal("3.000"), new BigDecimal("0.000"), new BigDecimal("3.000"))),
                movements(result.batchId()));
    }

    @Test
    void unknownBarcodeFailsBatchWithoutInventoryChanges() throws Exception {
        byte[] content = excel(
                row(ids.productB(), "1"),
                row(99_999L, "1"));

        ImportBatchResult result = useCase.importInventory(ids.store(), null, FILE_NAME, content);

        assertEquals(ImportBatchResult.STATUS_FAILED, result.status());
        assertEquals(2, result.totalRows());
        assertEquals(0, result.successRows());
        assertEquals(1, result.errorRows());
        assertEquals(1, result.errors().size());

        BatchRow batch = batch(result.batchId());
        assertEquals("FAILED", batch.status());
        assertFalse(batch.postedAtNotNull());
        assertTrue(batch.errorMessage().contains("条码不存在"));

        List<RawRow> rows = rawRows(result.batchId());
        assertEquals(2, rows.size());
        assertNull(rows.get(0).errorMessage());
        assertTrue(rows.get(1).errorMessage().contains("条码不存在"));

        assertNull(inventory(ids.store(), ids.productB()));
        assertEquals(0, countMovements(result.batchId()));
    }

    @Test
    void rejectsDuplicateFileAndExistingActiveBatch() throws Exception {
        byte[] content = excel(row(ids.productA(), "1"));
        useCase.importInventory(ids.store(), null, FILE_NAME, content);

        DuplicateImportFileException duplicate = assertThrows(DuplicateImportFileException.class,
                () -> useCase.importInventory(ids.store(), null, FILE_NAME, content));
        assertEquals("该文件已导入过: " + FILE_NAME, duplicate.getMessage());

        byte[] other = excel(row(ids.productA(), "2"));
        ActiveInitialBatchExistsException active = assertThrows(ActiveInitialBatchExistsException.class,
                () -> useCase.importInventory(ids.store(), null, "IT-另一份.xls", other));
        assertEquals("门店已有有效初始库存批次: " + ids.store(), active.getMessage());
    }

    @Test
    void failedBatchDoesNotBlockCorrectedReimport() throws Exception {
        byte[] broken = excel(row(ids.productB(), "1"), row(99_999L, "1"));
        useCase.importInventory(ids.store(), null, "IT-坏文件.xls", broken);

        byte[] fixed = excel(row(ids.productB(), "2"));
        ImportBatchResult result = useCase.importInventory(ids.store(), null, "IT-修正.xls", fixed);

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(new BigDecimal("2.000"), inventory(ids.store(), ids.productB()).quantity());
    }

    private StoreRepository storeRepository() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                throw new UnsupportedOperationException("集成测试不使用");
            }

            @Override
            public Optional<Store> findActiveById(long storeId) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, store_code, store_name FROM store WHERE id = ? AND is_active = 1")) {
                    statement.setLong(1, storeId);
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next()
                                ? Optional.of(new Store(
                                        result.getLong("id"),
                                        result.getString("store_code"),
                                        result.getString("store_name")))
                                : Optional.empty();
                    }
                } catch (SQLException exception) {
                    throw new IllegalStateException("查询门店失败", exception);
                }
            }

            @Override
            public boolean existsActiveById(long storeId) {
                throw new UnsupportedOperationException("集成测试不使用");
            }
        };
    }

    private WarehouseRepository warehouseRepository() {
        return new WarehouseRepository() {
            @Override
            public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
                throw new UnsupportedOperationException("集成测试不使用");
            }

            @Override
            public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM warehouse WHERE id = ? AND store_id = ?")) {
                    statement.setLong(1, warehouseId);
                    statement.setLong(2, storeId);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        return result.getLong(1) > 0;
                    }
                } catch (SQLException exception) {
                    throw new IllegalStateException("查询仓库失败", exception);
                }
            }
        };
    }

    private void insertFixtures(Connection connection, TestIds ids) throws SQLException {
        update(connection, "INSERT INTO store (id, store_code, store_name, is_active) VALUES (?, ?, ?, 1)",
                ids.store(), "IT-S-" + ids.suffix(), "集成测试导入店-" + ids.suffix());
        update(connection, "INSERT INTO warehouse (id, store_id, warehouse_code, warehouse_name, status) "
                        + "VALUES (?, ?, ?, ?, 'ENABLED'), (?, ?, ?, ?, 'ENABLED')",
                ids.warehouseOne(), ids.store(), "W1-" + ids.suffix(), "一仓",
                ids.warehouseTwo(), ids.store(), "W2-" + ids.suffix(), "二仓");
        update(connection, "INSERT INTO product (id, barcode, product_name, data_status) VALUES "
                        + "(?, ?, ?, 'ACTIVE'), (?, ?, ?, 'ACTIVE'), (?, ?, ?, 'ACTIVE')",
                ids.productA(), "6900" + ids.productA(), "导入商品甲-" + ids.suffix(),
                ids.productB(), "6900" + ids.productB(), "导入商品乙-" + ids.suffix(),
                ids.productC(), "6900" + ids.productC(), "导入商品丙-" + ids.suffix());
        update(connection, "INSERT INTO store_product_inventory "
                        + "(store_id, product_id, warehouse_id, current_quantity) VALUES (?, ?, ?, 5.000)",
                ids.store(), ids.productA(), ids.warehouseOne());
    }

    private BatchRow batch(long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT import_type, status, data_date, file_hash, error_message, posted_at "
                        + "FROM import_batch WHERE id = ?")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "批次应存在: " + batchId);
                return new BatchRow(
                        result.getString("import_type"),
                        result.getString("status"),
                        result.getObject("data_date", LocalDate.class),
                        result.getString("file_hash"),
                        result.getString("error_message"),
                        result.getTimestamp("posted_at") != null);
            }
        }
    }

    private List<RawRow> rawRows(long batchId) throws SQLException {
        List<RawRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT `row_number`, barcode, parse_status, error_message FROM import_raw_row "
                        + "WHERE batch_id = ? ORDER BY `row_number`")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new RawRow(
                            result.getLong("row_number"),
                            result.getString("barcode"),
                            result.getString("parse_status"),
                            result.getString("error_message")));
                }
            }
        }
        return rows;
    }

    private String rawData(long batchId, long rowNumber) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT raw_data FROM import_raw_row "
                        + "WHERE batch_id = ? AND `row_number` = ?")) {
            statement.setLong(1, batchId);
            statement.setLong(2, rowNumber);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "原始行应存在: batchId=" + batchId + ", rowNumber=" + rowNumber);
                return result.getString(1);
            }
        }
    }

    private Inventory inventory(long storeId, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT warehouse_id, current_quantity, version FROM store_product_inventory "
                        + "WHERE store_id = ? AND product_id = ?")) {
            statement.setLong(1, storeId);
            statement.setLong(2, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                long warehouseId = result.getLong("warehouse_id");
                return new Inventory(
                        result.wasNull() ? null : warehouseId,
                        result.getBigDecimal("current_quantity"),
                        result.getLong("version"));
            }
        }
    }

    private List<Movement> movements(long batchId) throws SQLException {
        List<Movement> movements = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT product_id, movement_type, business_date, quantity_change, "
                        + "balance_before, balance_after FROM inventory_movement "
                        + "WHERE batch_id = ? ORDER BY product_id")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    movements.add(new Movement(
                            result.getLong("product_id"),
                            result.getString("movement_type"),
                            result.getObject("business_date", LocalDate.class),
                            result.getBigDecimal("quantity_change"),
                            result.getBigDecimal("balance_before"),
                            result.getBigDecimal("balance_after")));
                }
            }
        }
        return movements;
    }

    private int countMovements(long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM inventory_movement WHERE batch_id = ?")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private DataRow row(long productId, String quantity) {
        return new DataRow("6900" + productId, quantity);
    }

    private byte[] excel(DataRow... rows) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("商品资料");
            List<String> headers = List.of(
                    "商品名称", "条码", "单位", "供应商名称", "含税成本价", "售价",
                    "毛利率", "品类编码", "品类名称", "库存数量", "商品备注", "提成率/固定值");
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            for (int index = 0; index < rows.length; index++) {
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue("测试商品" + (index + 1));
                row.createCell(1).setCellValue(rows[index].barcode());
                row.createCell(9).setCellValue(rows[index].quantity());
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private record TestIds(
            long base,
            String suffix,
            long store,
            long warehouseOne,
            long warehouseTwo,
            long productA,
            long productB,
            long productC) {

        static TestIds create() {
            long base = 8_200_000_000_000_000_000L
                    + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L) * 10_000L;
            String suffix = Long.toUnsignedString(base, 36);
            return new TestIds(
                    base, suffix,
                    base + 1, base + 10, base + 11,
                    base + 100, base + 101, base + 102);
        }
    }

    private record DataRow(String barcode, String quantity) {
    }

    private record BatchRow(
            String importType,
            String status,
            LocalDate dataDate,
            String fileHash,
            String errorMessage,
            boolean postedAtNotNull) {
    }

    private record RawRow(long rowNumber, String barcode, String parseStatus, String errorMessage) {
    }

    private record Inventory(Long warehouseId, BigDecimal quantity, long version) {
    }

    private record Movement(
            long productId,
            String movementType,
            LocalDate businessDate,
            BigDecimal quantityChange,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter) {
    }
}
