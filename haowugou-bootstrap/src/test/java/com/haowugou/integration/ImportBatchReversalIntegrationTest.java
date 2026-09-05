package com.haowugou.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.application.importbatch.ImportBatchDetailResult;
import com.haowugou.application.importbatch.ImportBatchPageResult;
import com.haowugou.application.importbatch.ImportBatchQuery;
import com.haowugou.application.importbatch.ReverseImportBatch;
import com.haowugou.application.importbatch.ReverseImportBatchResult;
import com.haowugou.application.importbatch.exception.BatchNotReversibleException;
import com.haowugou.application.importbatch.exception.ImportBatchNotFoundException;
import com.haowugou.application.inventoryimport.PostInitialInventoryImport;
import com.haowugou.application.salesimport.PostDailySalesImport;
import com.haowugou.domain.importbatch.ImportBatchListItem;
import com.haowugou.domain.importbatch.ImportBatchProblemRow;
import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.importbatch.ImportType;
import com.haowugou.domain.salesimport.DailySalesImportResult;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import com.haowugou.domain.warehouse.WarehouseSummary;
import com.haowugou.infrastructure.fileimport.PosDailySalesExcelFileParser;
import com.haowugou.infrastructure.fileimport.PosProductExcelFileParser;
import com.haowugou.infrastructure.persistence.importbatch.ImportBatchAdminMapper;
import com.haowugou.infrastructure.persistence.importbatch.ImportBatchMapper;
import com.haowugou.infrastructure.persistence.importbatch.MybatisImportBatchQueryRepository;
import com.haowugou.infrastructure.persistence.importbatch.MybatisImportBatchRepository;
import com.haowugou.infrastructure.persistence.importbatch.MybatisImportBatchReversalRepository;
import com.haowugou.infrastructure.persistence.salesimport.DailySalesImportMapper;
import com.haowugou.infrastructure.persistence.salesimport.MybatisDailySalesImportRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
 * 批次查询与撤销的 MySQL 全链路集成测试：真实导入用例把数据写进库，再用真实查询/撤销
 * 用例把它撤回来，逐表校验流水、库存与批次状态。门店与仓库用 JDBC 直查替身。
 *
 * <p>夹具使用高位 ID（8.4e18+）与 {@code IT-} 前缀，结束后通过 JDBC Connection 整体回滚。
 *
 * <p>依赖 {@code database/migration/2026-08-30-import-batch-active-file-hash.sql}：
 * 撤销后重传同一文件的用例需要 {@code active_file_hash} 生成列。
 */
class ImportBatchReversalIntegrationTest {

    /** POS《商品销售汇总》实测 15 列表头。 */
    private static final List<String> SALES_HEADERS = List.of(
            "条码", "商品名称", "本期|销售数量", "选中机构库存数量", "当前机构最后进价",
            "当前机构售价", "销售毛利率", "本期|销售收入", "销售占比", "日均销售",
            "同期|销售收入", "同期|销售毛利率", "同期|销售数量", "品类名称", "供应商名称");

    /** POS《商品资料》实测 12 列表头。 */
    private static final List<String> PRODUCT_HEADERS = List.of(
            "商品名称", "条码", "单位", "供应商名称", "含税成本价", "售价",
            "毛利率", "品类编码", "品类名称", "库存数量", "商品备注", "提成率/固定值");

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 29);
    private static final LocalDate INVENTORY_DATE = LocalDate.of(2026, 8, 20);
    private static final String SALES_FILE = "IT-商品销售汇总.xls";
    private static final String INVENTORY_FILE = "IT-商品资料.xls";
    private static final String OPERATOR = "IT-撤销员";
    private static final String REASON = "业务日期填错，需要重传";

    private SqlSession session;
    private Connection connection;
    private PostDailySalesImport postSales;
    private PostInitialInventoryImport postInventory;
    private ImportBatchQuery batchQuery;
    private ReverseImportBatch reverseBatch;
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
        // 三条链路的 Mapper 装进同一个 Configuration：一个 SqlSession 一个 JDBC 事务，末尾整体回滚。
        for (String resource : List.of(
                "mapper/ImportBatchMapper.xml",
                "mapper/DailySalesImportMapper.xml",
                "mapper/ImportBatchAdminMapper.xml")) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource,
                        configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(false);
        connection = session.getConnection();
        ids = TestIds.create();
        insertFixtures(connection, ids);

        StoreRepository stores = storeRepository();
        ImportBatchAdminMapper adminMapper = session.getMapper(ImportBatchAdminMapper.class);
        MybatisImportBatchQueryRepository queryRepository =
                new MybatisImportBatchQueryRepository(adminMapper);
        postSales = new PostDailySalesImport(
                stores,
                new MybatisDailySalesImportRepository(session.getMapper(DailySalesImportMapper.class)),
                new PosDailySalesExcelFileParser(new ObjectMapper()),
                () -> BUSINESS_DATE);
        postInventory = new PostInitialInventoryImport(
                stores,
                warehouseRepository(),
                new MybatisImportBatchRepository(session.getMapper(ImportBatchMapper.class)),
                new PosProductExcelFileParser(new ObjectMapper()),
                () -> INVENTORY_DATE);
        batchQuery = new ImportBatchQuery(stores, queryRepository);
        reverseBatch = new ReverseImportBatch(
                stores, queryRepository, new MybatisImportBatchReversalRepository(adminMapper));
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
    void reversingSalesBatchRestoresInventoryAndDropsBatchOutOfPostedView() throws Exception {
        DailySalesImportResult imported = postSales.importDailySales(ids.store(), BUSINESS_DATE,
                SALES_FILE, salesExcel(
                        sale(ids.productA(), "3", "36.00", "25.5", "天和日化"),
                        sale(ids.productB(), "2.5", "20.00", "10", "粮油商行")));
        assertEquals(ImportBatchResult.STATUS_POSTED, imported.status());
        assertEquals(new BigDecimal("2.000"), inventory(ids.store(), ids.productA()).quantity());
        assertEquals(new BigDecimal("-2.500"), inventory(ids.store(), ids.productB()).quantity());

        ReverseImportBatchResult reversed =
                reverseBatch.reverse(ids.store(), imported.batchId(), OPERATOR, REASON);

        assertEquals(imported.batchId(), reversed.batchId());
        assertEquals(ImportType.DAILY_SALES, reversed.importType());
        assertEquals(BUSINESS_DATE, reversed.dataDate());
        assertEquals(SALES_FILE, reversed.fileName());
        assertEquals(2, reversed.reversedMovements());
        assertEquals(2, reversed.restoredProducts());
        assertNotNull(reversed.reversedAt());
        assertEquals(ids.store(), reversed.store().id());

        // 库存回到导入前：甲 5 乙 0（乙的库存行由导入建出，撤销把它冲平但不删行）
        Inventory inventoryA = inventory(ids.store(), ids.productA());
        assertEquals(new BigDecimal("5.000"), inventoryA.quantity());
        assertEquals(2, inventoryA.version()); // 扣减一次、回滚一次
        assertEquals(ids.warehouseOne(), inventoryA.warehouseId()); // 撤销不动仓库分配
        assertEquals(new BigDecimal("0.000"), inventory(ids.store(), ids.productB()).quantity());

        // 原流水保留，反向流水按 REVERSAL 追加，余额链首尾相接
        assertEquals(List.of(
                new Movement(ids.productA(), "SALE_OUT", BUSINESS_DATE,
                        new BigDecimal("-3.000"), new BigDecimal("5.000"), new BigDecimal("2.000")),
                new Movement(ids.productB(), "SALE_OUT", BUSINESS_DATE,
                        new BigDecimal("-2.500"), new BigDecimal("0.000"), new BigDecimal("-2.500")),
                new Movement(ids.productA(), "REVERSAL", BUSINESS_DATE,
                        new BigDecimal("3.000"), new BigDecimal("2.000"), new BigDecimal("5.000")),
                new Movement(ids.productB(), "REVERSAL", BUSINESS_DATE,
                        new BigDecimal("2.500"), new BigDecimal("-2.500"), new BigDecimal("0.000"))),
                movements(imported.batchId()));
        assertEquals(movementIds(imported.batchId(), "SALE_OUT"),
                reversalTargets(imported.batchId()));

        BatchRow batch = batch(imported.batchId());
        assertEquals("REVERSED", batch.status());
        assertEquals(OPERATOR, batch.reversedBy());
        assertEquals(REASON, batch.reversedReason());
        assertNotNull(batch.reversedAt());
        assertNull(batch.activeSalesDate()); // 三个生成列同时释放坑位
        assertNull(batch.activeFileHash());
        assertNotNull(batch.fileHash()); // 原始指纹留档，只是不再占唯一键

        // 销售事实不删，靠视图把撤销批次排除在指标之外
        assertEquals(2, countSales(imported.batchId()));
        assertEquals(0, countPostedSales(imported.batchId()));
    }

    /** 撤销的业务价值所在：同一份文件、同一个业务日期可以原样重传。 */
    @Test
    void reversalReleasesFileHashAndDateSoSameFileCanBeReimported() throws Exception {
        byte[] content = salesExcel(sale(ids.productA(), "3", "36.00", "25.5", "天和日化"));
        DailySalesImportResult first =
                postSales.importDailySales(ids.store(), BUSINESS_DATE, SALES_FILE, content);
        reverseBatch.reverse(ids.store(), first.batchId(), OPERATOR, REASON);

        DailySalesImportResult second =
                postSales.importDailySales(ids.store(), BUSINESS_DATE, SALES_FILE, content);

        assertEquals(ImportBatchResult.STATUS_POSTED, second.status());
        assertEquals(BUSINESS_DATE, batch(second.batchId()).activeSalesDate());
        assertEquals(new BigDecimal("2.000"), inventory(ids.store(), ids.productA()).quantity());
        assertEquals(1, countPostedSales(second.batchId()));
        assertEquals(0, countPostedSales(first.batchId()));
    }

    @Test
    void secondReversalIsRejected() throws Exception {
        DailySalesImportResult imported = postSales.importDailySales(ids.store(), BUSINESS_DATE,
                SALES_FILE, salesExcel(sale(ids.productA(), "3", "36.00", "25.5", "天和日化")));
        reverseBatch.reverse(ids.store(), imported.batchId(), OPERATOR, REASON);

        BatchNotReversibleException rejected = assertThrows(BatchNotReversibleException.class,
                () -> reverseBatch.reverse(ids.store(), imported.batchId(), OPERATOR, "再撤一次"));
        assertEquals("该批次已撤销，不能重复撤销: batchId=" + imported.batchId() + ", status=REVERSED",
                rejected.getMessage());

        // 拒绝发生在写之前：库存与流水都不该被冲第二次
        assertEquals(new BigDecimal("5.000"), inventory(ids.store(), ids.productA()).quantity());
        assertEquals(1, countMovements(imported.batchId(), "REVERSAL"));
    }

    /**
     * 撤销初始库存批次会把库存打成负数——后续销售已经扣过，回滚只按流水冲原始增量。
     *
     * <p>这是有意的：库存表没有非负约束，硬拦会让「撤错的初始库存」变成无法收拾的状态。
     */
    @Test
    void reversingInitialInventoryAfterLaterSalesDrivesQuantityNegative() throws Exception {
        ImportBatchResult inventoryBatch = postInventory.importInventory(
                ids.store(), ids.warehouseTwo(), INVENTORY_FILE, productExcel(ids.productA(), "3"));
        assertEquals(ImportBatchResult.STATUS_POSTED, inventoryBatch.status());
        assertEquals(new BigDecimal("8.000"), inventory(ids.store(), ids.productA()).quantity());

        postSales.importDailySales(ids.store(), BUSINESS_DATE, SALES_FILE,
                salesExcel(sale(ids.productA(), "10", "120.00", "25.5", "天和日化")));
        assertEquals(new BigDecimal("-2.000"), inventory(ids.store(), ids.productA()).quantity());

        ReverseImportBatchResult reversed =
                reverseBatch.reverse(ids.store(), inventoryBatch.batchId(), OPERATOR, "期初填错");

        assertEquals(ImportType.INITIAL_INVENTORY, reversed.importType());
        assertEquals(1, reversed.reversedMovements());
        assertEquals(new BigDecimal("-5.000"), inventory(ids.store(), ids.productA()).quantity());
        // 反向流水的 balance_before 取撤销时刻的真实余额，不是原流水的 balance_after
        assertEquals(List.of(new Movement(ids.productA(), "REVERSAL", INVENTORY_DATE,
                        new BigDecimal("-3.000"), new BigDecimal("-2.000"), new BigDecimal("-5.000"))),
                movementsOfType(inventoryBatch.batchId(), "REVERSAL"));
        assertNull(batch(inventoryBatch.batchId()).activeInitialInventory());
    }

    @Test
    void listFiltersByTypeAndStatusAndNeverLeaksOtherStores() throws Exception {
        DailySalesImportResult mine = postSales.importDailySales(ids.store(), BUSINESS_DATE,
                SALES_FILE, salesExcel(sale(ids.productA(), "1", "12.00", "25.5", "天和日化")));
        ImportBatchResult mineInventory = postInventory.importInventory(
                ids.store(), ids.warehouseTwo(), INVENTORY_FILE, productExcel(ids.productA(), "1"));
        DailySalesImportResult theirs = postSales.importDailySales(ids.otherStore(), BUSINESS_DATE,
                "IT-别家.xls", salesExcel(sale(ids.productA(), "9", "99.00", "25.5", "天和日化")));

        List<Long> mineIds = batchIds(all(ids.store()));
        assertTrue(mineIds.contains(mine.batchId()));
        assertTrue(mineIds.contains(mineInventory.batchId()));
        assertFalse(mineIds.contains(theirs.batchId()), "列表不能出现其他门店的批次");

        assertEquals(List.of(mine.batchId()), batchIds(batchQuery.listBatches(criteria(
                ids.store(), ImportType.DAILY_SALES, null))));
        assertEquals(List.of(mineInventory.batchId()), batchIds(batchQuery.listBatches(criteria(
                ids.store(), ImportType.INITIAL_INVENTORY, null))));
        assertEquals(List.of(), batchIds(batchQuery.listBatches(criteria(
                ids.store(), null, ImportBatchStatus.REVERSED))));

        reverseBatch.reverse(ids.store(), mine.batchId(), OPERATOR, REASON);
        assertEquals(List.of(mine.batchId()), batchIds(batchQuery.listBatches(criteria(
                ids.store(), null, ImportBatchStatus.REVERSED))));

        // 详情与撤销都按 (batchId, storeId) 取，跨门店直接当不存在
        assertThrows(ImportBatchNotFoundException.class,
                () -> batchQuery.findBatch(ids.store(), theirs.batchId(), 0, 20));
        assertThrows(ImportBatchNotFoundException.class,
                () -> reverseBatch.reverse(ids.store(), theirs.batchId(), OPERATOR, REASON));
    }

    /** 失败批次的价值全在问题行上；它没写过库存，所以不许撤销。 */
    @Test
    void failedBatchExposesProblemRowsAndCannotBeReversed() throws Exception {
        DailySalesImportResult failed = postSales.importDailySales(ids.store(), BUSINESS_DATE,
                SALES_FILE, salesExcel(
                        sale(ids.productA(), "1", "12.00", "25.5", "天和日化"),
                        sale(ids.productB(), "abc", "20.00", "10", "粮油商行")));
        assertEquals(ImportBatchResult.STATUS_FAILED, failed.status());

        ImportBatchDetailResult detail = batchQuery.findBatch(ids.store(), failed.batchId(), 0, 20);

        assertEquals(ImportBatchStatus.FAILED, detail.batch().status());
        assertFalse(detail.batch().reversible());
        assertNull(detail.batch().operatorName()); // 导入链路目前不落操作人
        assertNotNull(detail.batch().fileHash());
        assertTrue(detail.batch().errorMessage().contains("销售数量无法解析"));

        // 只返回非 VALID 行：120 行文件里的 2 条问题行才是要看的
        assertEquals(1, detail.problemRows().totalElements());
        ImportBatchProblemRow problem = detail.problemRows().items().getFirst();
        assertEquals(3, problem.rowNumber());
        assertEquals("6900" + ids.productB(), problem.barcode());
        assertEquals("INVALID", problem.parseStatus());
        assertTrue(problem.errorMessage().contains("销售数量无法解析"));

        BatchNotReversibleException rejected = assertThrows(BatchNotReversibleException.class,
                () -> reverseBatch.reverse(ids.store(), failed.batchId(), OPERATOR, REASON));
        assertEquals("失败批次未产生库存变化，无需撤销: batchId=" + failed.batchId() + ", status=FAILED",
                rejected.getMessage());
    }

    /** 问题行分页独立于批次元信息，翻页不影响批次本身。 */
    @Test
    void problemRowsArePagedIndependently() throws Exception {
        DailySalesImportResult failed = postSales.importDailySales(ids.store(), BUSINESS_DATE,
                SALES_FILE, salesExcel(
                        sale(ids.productA(), "x", "12.00", "25.5", "天和日化"),
                        sale(ids.productB(), "y", "20.00", "10", "粮油商行"),
                        sale(ids.productC(), "z", "8.00", "10", "粮油商行")));

        ImportBatchDetailResult firstPage = batchQuery.findBatch(ids.store(), failed.batchId(), 0, 2);
        assertEquals(3, firstPage.problemRows().totalElements());
        assertEquals(2, firstPage.problemRows().totalPages());
        assertEquals(List.of(2L, 3L), rowNumbers(firstPage));

        ImportBatchDetailResult secondPage = batchQuery.findBatch(ids.store(), failed.batchId(), 1, 2);
        assertEquals(List.of(4L), rowNumbers(secondPage));
        assertEquals(failed.batchId(), secondPage.batch().batchId());
    }

    private ImportBatchPageResult all(long storeId) {
        return batchQuery.listBatches(criteria(storeId, null, null));
    }

    private ImportBatchQueryCriteria criteria(
            long storeId, ImportType importType, ImportBatchStatus status) {
        return new ImportBatchQueryCriteria(storeId, importType, status, null, null, 0, 20);
    }

    private List<Long> batchIds(ImportBatchPageResult result) {
        return result.batches().items().stream().map(ImportBatchListItem::batchId).toList();
    }

    private List<Long> rowNumbers(ImportBatchDetailResult result) {
        return result.problemRows().items().stream().map(ImportBatchProblemRow::rowNumber).toList();
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
        update(connection, "INSERT INTO store (id, store_code, store_name, is_active) "
                        + "VALUES (?, ?, ?, 1), (?, ?, ?, 1)",
                ids.store(), "IT-S-" + ids.suffix(), "集成测试撤销店-" + ids.suffix(),
                ids.otherStore(), "IT-O-" + ids.suffix(), "集成测试隔离店-" + ids.suffix());
        update(connection, "INSERT INTO warehouse (id, store_id, warehouse_code, warehouse_name, status) "
                        + "VALUES (?, ?, ?, ?, 'ENABLED'), (?, ?, ?, ?, 'ENABLED')",
                ids.warehouseOne(), ids.store(), "W1-" + ids.suffix(), "一仓",
                ids.warehouseTwo(), ids.store(), "W2-" + ids.suffix(), "二仓");
        update(connection, "INSERT INTO supplier (id, supplier_name) VALUES (?, ?)",
                ids.supplier(), "天和日化");
        update(connection, "INSERT INTO product (id, barcode, product_name, data_status) VALUES "
                        + "(?, ?, ?, 'ACTIVE'), (?, ?, ?, 'ACTIVE'), (?, ?, ?, 'ACTIVE')",
                ids.productA(), "6900" + ids.productA(), "撤销商品甲-" + ids.suffix(),
                ids.productB(), "6900" + ids.productB(), "撤销商品乙-" + ids.suffix(),
                ids.productC(), "6900" + ids.productC(), "撤销商品丙-" + ids.suffix());
        update(connection, "INSERT INTO store_product_inventory "
                        + "(store_id, product_id, warehouse_id, current_quantity) VALUES (?, ?, ?, 5.000)",
                ids.store(), ids.productA(), ids.warehouseOne());
    }

    private BatchRow batch(long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, file_hash, active_file_hash, active_sales_date, "
                        + "active_initial_inventory, reversed_at, reversed_by, reversed_reason "
                        + "FROM import_batch WHERE id = ?")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "批次应存在: " + batchId);
                // wasNull() 只对最近一次读取有效，必须紧跟 getInt 记录下来
                int activeInitial = result.getInt("active_initial_inventory");
                Integer nullableActiveInitial = result.wasNull() ? null : activeInitial;
                return new BatchRow(
                        result.getString("status"),
                        result.getString("file_hash"),
                        result.getString("active_file_hash"),
                        result.getObject("active_sales_date", LocalDate.class),
                        nullableActiveInitial,
                        result.getTimestamp("reversed_at"),
                        result.getString("reversed_by"),
                        result.getString("reversed_reason"));
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

    /** 原流水在前、反向流水在后，各自按商品排序——不依赖插入顺序，断言才稳。 */
    private static final String MOVEMENT_ORDER =
            " ORDER BY m.movement_type = 'REVERSAL', m.product_id, m.id";

    private List<Movement> movements(long batchId) throws SQLException {
        return queryMovements("SELECT m.product_id, m.movement_type, m.business_date, "
                + "m.quantity_change, m.balance_before, m.balance_after FROM inventory_movement m "
                + "WHERE m.batch_id = ?" + MOVEMENT_ORDER, batchId);
    }

    private List<Movement> movementsOfType(long batchId, String movementType) throws SQLException {
        return queryMovements("SELECT m.product_id, m.movement_type, m.business_date, "
                + "m.quantity_change, m.balance_before, m.balance_after FROM inventory_movement m "
                + "WHERE m.batch_id = ? AND m.movement_type = '" + movementType + "'"
                + MOVEMENT_ORDER, batchId);
    }

    private List<Movement> queryMovements(String sql, long batchId) throws SQLException {
        List<Movement> movements = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    private List<Long> movementIds(long batchId, String movementType) throws SQLException {
        return longs("SELECT m.id FROM inventory_movement m WHERE m.batch_id = ? "
                + "AND m.movement_type = '" + movementType + "'" + MOVEMENT_ORDER, batchId);
    }

    private List<Long> reversalTargets(long batchId) throws SQLException {
        return longs("SELECT m.reversal_of_id FROM inventory_movement m WHERE m.batch_id = ? "
                + "AND m.movement_type = 'REVERSAL'" + MOVEMENT_ORDER, batchId);
    }

    private List<Long> longs(String sql, long batchId) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getLong(1));
                }
            }
        }
        return values;
    }

    private int countMovements(long batchId, String movementType) throws SQLException {
        return count("SELECT COUNT(*) FROM inventory_movement WHERE batch_id = ? "
                + "AND movement_type = '" + movementType + "'", batchId);
    }

    private int countSales(long batchId) throws SQLException {
        return count("SELECT COUNT(*) FROM daily_product_sales WHERE batch_id = ?", batchId);
    }

    private int countPostedSales(long batchId) throws SQLException {
        return count("SELECT COUNT(*) FROM v_posted_daily_product_sales WHERE batch_id = ?", batchId);
    }

    private int count(String sql, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
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

    private SalesRow sale(
            long productId, String quantity, String amount, String rate, String supplierName) {
        return new SalesRow("6900" + productId, quantity, amount, rate, supplierName);
    }

    private byte[] salesExcel(SalesRow... rows) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("商品销售汇总");
            writeHeader(sheet, SALES_HEADERS);
            for (int index = 0; index < rows.length; index++) {
                SalesRow data = rows[index];
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(data.barcode());
                row.createCell(1).setCellValue("撤销测试商品" + (index + 1));
                row.createCell(2).setCellValue(data.quantity());
                row.createCell(6).setCellValue(data.rate());
                row.createCell(7).setCellValue(data.amount());
                row.createCell(14).setCellValue(data.supplierName());
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] productExcel(long productId, String quantity) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("商品资料");
            writeHeader(sheet, PRODUCT_HEADERS);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("撤销测试期初商品");
            row.createCell(1).setCellValue("6900" + productId);
            row.createCell(9).setCellValue(quantity);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void writeHeader(Sheet sheet, List<String> headers) {
        Row header = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            header.createCell(column).setCellValue(headers.get(column));
        }
    }

    private record TestIds(
            String suffix,
            long store,
            long otherStore,
            long warehouseOne,
            long warehouseTwo,
            long supplier,
            long productA,
            long productB,
            long productC) {

        static TestIds create() {
            long base = 8_400_000_000_000_000_000L
                    + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L) * 10_000L;
            return new TestIds(
                    Long.toUnsignedString(base, 36),
                    base + 1, base + 2, base + 10, base + 11, base + 30,
                    base + 100, base + 101, base + 102);
        }
    }

    private record SalesRow(
            String barcode, String quantity, String amount, String rate, String supplierName) {
    }

    private record BatchRow(
            String status,
            String fileHash,
            String activeFileHash,
            LocalDate activeSalesDate,
            Integer activeInitialInventory,
            java.sql.Timestamp reversedAt,
            String reversedBy,
            String reversedReason) {
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
