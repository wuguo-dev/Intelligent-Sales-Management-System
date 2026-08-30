package com.haowugou.application.salesimport;

import com.haowugou.application.operating.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.importbatch.ImportFailureRow;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.importbatch.ImportRowError;
import com.haowugou.domain.salesimport.DailySalesFactRow;
import com.haowugou.domain.salesimport.DailySalesFileParser;
import com.haowugou.domain.salesimport.DailySalesImportRepository;
import com.haowugou.domain.salesimport.DailySalesImportResult;
import com.haowugou.domain.salesimport.DailySalesPosting;
import com.haowugou.domain.salesimport.ParsedSalesFile;
import com.haowugou.domain.salesimport.ParsedSalesRow;
import com.haowugou.domain.salesimport.PendingProductDraft;
import com.haowugou.domain.salesimport.SalesMovement;
import com.haowugou.domain.store.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 按门店导入每日销售数据并扣减库存的写入用例。
 *
 * <p>流程：门店与业务日期校验 → 文件级校验 → SHA-256 查重 → 该日有效批次检查 → 解析
 * → 行级校验 → 未知条码建待完善商品 → 按 {@code (商品, 供应商)} 归并销售事实、按商品汇总净销量
 * → 全有或全无（任何行级错误则整批 FAILED，不产生销售与库存变化；全部通过则单事务过账）。
 *
 * <p>毛利额由「销售收入 × POS 销售毛利率」推算：文件里的进价是当前最后进价，实测与 POS 当时口径
 * 不一致，不能用来倒算。POS 原始毛利率同时原样留档供核对。
 */
public final class PostDailySalesImport {

    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[0-9A-Za-z-]+$");
    private static final int BARCODE_MAX_LENGTH = 64;
    private static final int PRODUCT_NAME_MAX_LENGTH = 255;
    private static final int QUANTITY_SCALE = 3;
    private static final int AMOUNT_SCALE = 2;
    private static final int RATE_SCALE = 4;
    private static final int PRICE_SCALE = 4;
    private static final int ERROR_SUMMARY_LINES = 10;
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final StoreRepository storeRepository;
    private final DailySalesImportRepository salesRepository;
    private final DailySalesFileParser fileParser;
    private final Supplier<LocalDate> today;

    public PostDailySalesImport(
            StoreRepository storeRepository,
            DailySalesImportRepository salesRepository,
            DailySalesFileParser fileParser,
            Supplier<LocalDate> today) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.salesRepository = Objects.requireNonNull(salesRepository);
        this.fileParser = Objects.requireNonNull(fileParser);
        this.today = Objects.requireNonNull(today);
    }

    public DailySalesImportResult importDailySales(
            long storeId, LocalDate businessDate, String fileName, byte[] content) {
        if (storeId <= 0) {
            throw new InvalidSalesImportException("门店ID必须大于0");
        }
        validateBusinessDate(businessDate);
        validateFileInput(fileName, content);
        storeRepository.findActiveById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));

        String fileHash = sha256Hex(content);
        if (salesRepository.existsFileHash(storeId, fileHash)) {
            throw new DuplicateSalesFileException(fileName);
        }
        if (salesRepository.existsPostedSalesBatch(storeId, businessDate)) {
            throw new PostedSalesBatchExistsException(storeId, businessDate);
        }

        List<ParsedSalesRow> rows = parseFile(content, fileName).rows();
        if (rows.isEmpty()) {
            throw new InvalidSalesImportException("文件中没有数据行");
        }

        List<ImportRowError> errors = new ArrayList<>();
        List<ValidatedRow> validated = new ArrayList<>();
        Map<String, Long> seenKeys = new LinkedHashMap<>();
        for (ParsedSalesRow row : rows) {
            validateRow(row, errors, validated, seenKeys);
        }
        if (!errors.isEmpty()) {
            return saveFailed(storeId, fileName, fileHash, businessDate, rows, errors);
        }

        // 数量与收入同时为 0 的行不构成销售事实：不落库、不产生流水、不创建待完善商品
        List<ValidatedRow> salesRows = validated.stream().filter(ValidatedRow::hasSales).toList();
        Map<String, Long> knownProductIds = salesRepository.findProductIdsByBarcodes(
                salesRows.stream().map(ValidatedRow::barcode).distinct().toList());
        List<PendingProductDraft> pendingProducts =
                draftPendingProducts(salesRows, knownProductIds, errors);
        if (!errors.isEmpty()) {
            return saveFailed(storeId, fileName, fileHash, businessDate, rows, errors);
        }

        Map<String, Long> supplierIds = salesRepository.findSupplierIdsByNames(
                salesRows.stream()
                        .map(ValidatedRow::supplierName)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        List<DailySalesFactRow> factRows = mergeFactRows(salesRows, supplierIds);
        List<SalesMovement> movements = buildMovements(salesRows);

        long batchId = salesRepository.postBatch(new DailySalesPosting(
                storeId,
                fileName,
                fileHash,
                businessDate,
                rows,
                knownProductIds,
                pendingProducts,
                factRows,
                movements));
        return new DailySalesImportResult(
                batchId,
                ImportBatchResult.STATUS_POSTED,
                rows.size(),
                rows.size(),
                0,
                factRows.size(),
                pendingProducts.size(),
                movements.size(),
                List.of());
    }

    private void validateBusinessDate(LocalDate businessDate) {
        if (businessDate == null) {
            throw new InvalidSalesImportException("缺少销售业务日期");
        }
        if (businessDate.isAfter(today.get())) {
            throw new InvalidSalesImportException("销售业务日期不能晚于今天: " + businessDate);
        }
    }

    private void validateFileInput(String fileName, byte[] content) {
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidSalesImportException("缺少文件名");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xls") && !lowerName.endsWith(".xlsx")) {
            throw new InvalidSalesImportException("仅支持 .xls 或 .xlsx 文件: " + fileName);
        }
        if (content == null || content.length == 0) {
            throw new InvalidSalesImportException("上传文件为空");
        }
    }

    private ParsedSalesFile parseFile(byte[] content, String fileName) {
        try {
            return fileParser.parse(content, fileName);
        } catch (ImportFileFormatException exception) {
            throw new InvalidSalesImportException(exception.getMessage());
        }
    }

    /** 行级校验：条码、数量、收入、毛利率与文件内 {@code (条码, 供应商)} 重复。 */
    private void validateRow(
            ParsedSalesRow row,
            List<ImportRowError> errors,
            List<ValidatedRow> validated,
            Map<String, Long> seenKeys) {
        String barcode = row.barcode();
        if (barcode == null || barcode.isBlank()) {
            errors.add(new ImportRowError(row.rowNumber(), "", "条码为空"));
            return;
        }
        if (!BARCODE_PATTERN.matcher(barcode).matches()) {
            errors.add(new ImportRowError(row.rowNumber(), barcode, "条码格式非法: " + barcode));
            return;
        }
        if (barcode.length() > BARCODE_MAX_LENGTH) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode, "条码长度不能超过" + BARCODE_MAX_LENGTH + "字符"));
            return;
        }
        String supplierName = blankToNull(row.supplierName());
        String dedupKey = barcode + ' ' + (supplierName == null ? "" : supplierName);
        Long firstRow = seenKeys.putIfAbsent(dedupKey, row.rowNumber());
        if (firstRow != null) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode,
                    "同条码同供应商在文件中重复（首次出现于第" + firstRow + "行）: " + barcode));
            return;
        }

        // 数量与收入空白按 0 处理：这类行本就不构成销售事实，报错只会让整批被卡住
        NumberCell quantity = numberCell(row.salesQuantity(), BigDecimal.ZERO);
        if (quantity.invalid()) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode, "销售数量无法解析: " + row.salesQuantity()));
            return;
        }
        if (quantity.value().scale() > QUANTITY_SCALE) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode, "销售数量小数位不能超过3位: " + row.salesQuantity()));
            return;
        }
        NumberCell amount = numberCell(row.salesAmount(), BigDecimal.ZERO);
        if (amount.invalid()) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode, "销售收入无法解析: " + row.salesAmount()));
            return;
        }
        if (amount.value().scale() > AMOUNT_SCALE) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode, "销售收入小数位不能超过2位: " + row.salesAmount()));
            return;
        }
        // 毛利率空白表示 POS 未报，与 0% 含义不同，因此缺省为 null
        NumberCell rate = numberCell(row.grossProfitRate(), null);
        if (rate.invalid()) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode, "销售毛利率无法解析: " + row.grossProfitRate()));
            return;
        }
        if (rate.value() != null && rate.value().scale() > RATE_SCALE) {
            errors.add(new ImportRowError(
                    row.rowNumber(), barcode, "销售毛利率小数位不能超过4位: " + row.grossProfitRate()));
            return;
        }
        validated.add(new ValidatedRow(
                row, barcode, supplierName, quantity.value(), amount.value(), rate.value()));
    }

    /**
     * 未知条码建待完善商品：销售事实必须入账，因此按文件里的名称、品类与价格建 PENDING 商品。
     *
     * <p>品类按名称匹配现有主数据，匹配不到记 null，不自动创建品类。
     */
    private List<PendingProductDraft> draftPendingProducts(
            List<ValidatedRow> salesRows,
            Map<String, Long> knownProductIds,
            List<ImportRowError> errors) {
        Map<String, ValidatedRow> unknown = new LinkedHashMap<>();
        for (ValidatedRow row : salesRows) {
            String barcode = row.barcode();
            if (knownProductIds.containsKey(barcode) || unknown.containsKey(barcode)) {
                continue;
            }
            String productName = blankToNull(row.source().productName());
            if (productName == null) {
                errors.add(new ImportRowError(
                        row.rowNumber(), barcode, "条码不存在且缺少商品名称，无法建待完善商品: " + barcode));
                continue;
            }
            if (productName.length() > PRODUCT_NAME_MAX_LENGTH) {
                errors.add(new ImportRowError(
                        row.rowNumber(), barcode,
                        "商品名称长度不能超过" + PRODUCT_NAME_MAX_LENGTH + "字符: " + barcode));
                continue;
            }
            unknown.put(barcode, row);
        }
        if (!errors.isEmpty() || unknown.isEmpty()) {
            return List.of();
        }

        Map<String, Long> categoryIds = salesRepository.findCategoryIdsByNames(
                unknown.values().stream()
                        .map(row -> blankToNull(row.source().categoryName()))
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        return unknown.values().stream()
                .map(row -> new PendingProductDraft(
                        row.barcode(),
                        blankToNull(row.source().productName()),
                        lookup(categoryIds, blankToNull(row.source().categoryName())),
                        price(row.source().taxCostPrice()),
                        price(row.source().salePrice())))
                .toList();
    }

    /** 按 {@code (条码, 供应商)} 归并销售事实，与 {@code uk_daily_sales_batch_product_supplier} 同口径。 */
    private List<DailySalesFactRow> mergeFactRows(
            List<ValidatedRow> salesRows, Map<String, Long> supplierIds) {
        Map<String, MergedFact> merged = new LinkedHashMap<>();
        for (ValidatedRow row : salesRows) {
            Long supplierId = lookup(supplierIds, row.supplierName());
            String key = row.barcode() + ' ' + (supplierId == null ? "0" : supplierId);
            MergedFact fact = new MergedFact(
                    row.barcode(), supplierId, row.quantity(), row.amount(),
                    grossProfit(row.amount(), row.rate()), row.rate());
            merged.merge(key, fact, MergedFact::plus);
        }
        return merged.values().stream()
                .map(fact -> new DailySalesFactRow(
                        fact.barcode(), fact.supplierId(), fact.quantity(), fact.amount(),
                        fact.grossProfit(), fact.reportedRate()))
                .toList();
    }

    /** 按商品汇总净销量得到库存流水：净销量为 0 的商品不产生流水（数据库禁止 0 变化量）。 */
    private List<SalesMovement> buildMovements(List<ValidatedRow> salesRows) {
        Map<String, BigDecimal> netByBarcode = new LinkedHashMap<>();
        for (ValidatedRow row : salesRows) {
            netByBarcode.merge(row.barcode(), row.quantity(), BigDecimal::add);
        }
        List<SalesMovement> movements = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : netByBarcode.entrySet()) {
            BigDecimal net = entry.getValue();
            if (net.signum() == 0) {
                continue;
            }
            movements.add(new SalesMovement(
                    entry.getKey(),
                    net.negate(),
                    net.signum() > 0 ? SalesMovement.TYPE_SALE_OUT : SalesMovement.TYPE_SALE_RETURN));
        }
        return movements;
    }

    private DailySalesImportResult saveFailed(
            long storeId,
            String fileName,
            String fileHash,
            LocalDate businessDate,
            List<ParsedSalesRow> rows,
            List<ImportRowError> errors) {
        Map<Long, String> messageByRow = errors.stream().collect(Collectors.toMap(
                ImportRowError::rowNumber, ImportRowError::message, (first, second) -> first));
        List<ImportFailureRow> failureRows = rows.stream()
                .map(row -> new ImportFailureRow(
                        row.rowNumber(), row.barcode(), row.rawData(), messageByRow.get(row.rowNumber())))
                .toList();
        String summary = errors.stream()
                .limit(ERROR_SUMMARY_LINES)
                .map(error -> "第" + error.rowNumber() + "行: " + error.message())
                .collect(Collectors.joining("; "));
        long batchId = salesRepository.saveFailedBatch(
                new ImportFailure(storeId, fileName, fileHash, businessDate, failureRows, summary));
        return new DailySalesImportResult(
                batchId,
                ImportBatchResult.STATUS_FAILED,
                rows.size(),
                0,
                countErrorRows(errors),
                0,
                0,
                0,
                List.copyOf(errors));
    }

    /** 同一行最多算一个错误，保证 {@code success_rows + error_rows <= total_rows}。 */
    private int countErrorRows(List<ImportRowError> errors) {
        return (int) errors.stream().map(ImportRowError::rowNumber).distinct().count();
    }

    /** 毛利额 = 销售收入 × POS 毛利率 ÷ 100，保留 2 位；毛利率缺失时无法推算。 */
    private BigDecimal grossProfit(BigDecimal amount, BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        return amount.multiply(rate).divide(PERCENT, AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /** 价格仅作待完善商品初值：超出库表小数位直接舍入，解析不了记 null，不阻断销售入账。 */
    private BigDecimal price(String text) {
        NumberCell cell = numberCell(text, null);
        if (cell.invalid() || cell.value() == null) {
            return null;
        }
        return cell.value().scale() > PRICE_SCALE
                ? cell.value().setScale(PRICE_SCALE, RoundingMode.HALF_UP)
                : cell.value();
    }

    private NumberCell numberCell(String text, BigDecimal blankValue) {
        if (text == null || text.isBlank()) {
            return new NumberCell(blankValue, false);
        }
        try {
            return new NumberCell(new BigDecimal(text.trim()), false);
        } catch (NumberFormatException exception) {
            return new NumberCell(null, true);
        }
    }

    private Long lookup(Map<String, Long> ids, String name) {
        return name == null ? null : ids.get(name);
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String sha256Hex(byte[] content) {
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

    /** {@code invalid} 区分「单元格空白（取缺省值）」与「有内容但解析不了（行级错误）」。 */
    private record NumberCell(BigDecimal value, boolean invalid) {
    }

    private record ValidatedRow(
            ParsedSalesRow source,
            String barcode,
            String supplierName,
            BigDecimal quantity,
            BigDecimal amount,
            BigDecimal rate) {

        long rowNumber() {
            return source.rowNumber();
        }

        /** 数量与收入同时为 0 的行不构成销售事实。 */
        boolean hasSales() {
            return quantity.signum() != 0 || amount.signum() != 0;
        }
    }

    /** 归并中的销售事实；多行归并后 POS 原始毛利率无法归属单一原始值，记 null。 */
    private record MergedFact(
            String barcode,
            Long supplierId,
            BigDecimal quantity,
            BigDecimal amount,
            BigDecimal grossProfit,
            BigDecimal reportedRate) {

        MergedFact plus(MergedFact other) {
            BigDecimal sumProfit = grossProfit == null || other.grossProfit == null
                    ? null
                    : grossProfit.add(other.grossProfit);
            return new MergedFact(
                    barcode,
                    supplierId,
                    quantity.add(other.quantity),
                    amount.add(other.amount),
                    sumProfit,
                    null);
        }
    }
}
