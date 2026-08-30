package com.haowugou.application.inventoryimport;

import com.haowugou.application.inventoryimport.exception.ActiveInitialBatchExistsException;
import com.haowugou.application.inventoryimport.exception.DuplicateImportFileException;
import com.haowugou.application.inventoryimport.exception.ImportWarehouseException;
import com.haowugou.application.inventoryimport.exception.InvalidImportFileException;
import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportBatchRepository;
import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.importbatch.ImportFailureRow;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.importbatch.ImportFileParser;
import com.haowugou.domain.importbatch.ImportPostRow;
import com.haowugou.domain.importbatch.ImportPosting;
import com.haowugou.domain.importbatch.ImportRowError;
import com.haowugou.domain.importbatch.ParsedImportFile;
import com.haowugou.domain.importbatch.ParsedImportRow;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import java.math.BigDecimal;
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
 * 按门店导入初始库存的写入用例。
 *
 * <p>流程：门店校验 → 文件级校验 → SHA-256 查重 → 有效批次检查 → 解析 → 行校验与商品归并
 * → 全有或全无（任何行级错误则整批 FAILED，不产生库存变化；全部通过则单事务过账）。
 * 仓库为可选分配：不传时库存行 {@code warehouse_id} 为空（待分配），后续编辑页面再指定。
 */
public final class PostInitialInventoryImport {

    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[0-9A-Za-z-]+$");
    private static final int QUANTITY_SCALE = 3;
    private static final int ERROR_SUMMARY_LINES = 10;

    private final StoreRepository storeRepository;
    private final WarehouseRepository warehouseRepository;
    private final ImportBatchRepository importRepository;
    private final ImportFileParser fileParser;
    private final Supplier<LocalDate> today;

    public PostInitialInventoryImport(
            StoreRepository storeRepository,
            WarehouseRepository warehouseRepository,
            ImportBatchRepository importRepository,
            ImportFileParser fileParser,
            Supplier<LocalDate> today) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.warehouseRepository = Objects.requireNonNull(warehouseRepository);
        this.importRepository = Objects.requireNonNull(importRepository);
        this.fileParser = Objects.requireNonNull(fileParser);
        this.today = Objects.requireNonNull(today);
    }

    public ImportBatchResult importInventory(
            long storeId, Long warehouseId, String fileName, byte[] content) {
        requirePositive(storeId, "门店ID");
        validateFileInput(fileName, content);
        storeRepository.findActiveById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        validateWarehouse(storeId, warehouseId);

        String fileHash = sha256Hex(content);
        if (importRepository.existsFileHash(storeId, fileHash)) {
            throw new DuplicateImportFileException(fileName);
        }
        if (importRepository.existsActiveInitialBatch(storeId)) {
            throw new ActiveInitialBatchExistsException(storeId);
        }

        List<ParsedImportRow> rows = parseFile(content, fileName).rows();
        if (rows.isEmpty()) {
            throw new InvalidImportFileException("文件中没有数据行");
        }
        LocalDate dataDate = today.get();

        List<ImportRowError> errors = new ArrayList<>();
        Map<String, ValidatedRow> validated = new LinkedHashMap<>();
        for (ParsedImportRow row : rows) {
            validateRow(row, errors, validated);
        }

        // 行级错误已存在时整批必然失败，不再叠加「条码不存在」噪声错误
        if (errors.isEmpty()) {
            Map<String, Long> productIds = importRepository
                    .findProductIdsByBarcodes(new ArrayList<>(validated.keySet()));
            for (Map.Entry<String, ValidatedRow> entry : validated.entrySet()) {
                if (!productIds.containsKey(entry.getKey())) {
                    errors.add(new ImportRowError(
                            entry.getValue().row().rowNumber(), entry.getKey(), "条码不存在: " + entry.getKey()));
                }
            }
            if (errors.isEmpty()) {
                return post(storeId, fileName, fileHash, dataDate, warehouseId, rows, validated, productIds);
            }
        }
        return saveFailed(storeId, fileName, fileHash, dataDate, rows, errors);
    }

    private void validateFileInput(String fileName, byte[] content) {
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidImportFileException("缺少文件名");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xls") && !lowerName.endsWith(".xlsx")) {
            throw new InvalidImportFileException("仅支持 .xls 或 .xlsx 文件: " + fileName);
        }
        if (content == null || content.length == 0) {
            throw new InvalidImportFileException("上传文件为空");
        }
    }

    private void validateWarehouse(long storeId, Long warehouseId) {
        if (warehouseId == null) {
            return;
        }
        if (warehouseId <= 0
                || !warehouseRepository.existsByStoreIdAndId(storeId, warehouseId)) {
            throw new ImportWarehouseException(storeId, warehouseId);
        }
    }

    private void validateRow(
            ParsedImportRow row,
            List<ImportRowError> errors,
            Map<String, ValidatedRow> validated) {
        String barcode = row.barcode();
        if (barcode == null || barcode.isBlank()) {
            errors.add(new ImportRowError(row.rowNumber(), "", "条码为空"));
            return;
        }
        if (!BARCODE_PATTERN.matcher(barcode).matches()) {
            errors.add(new ImportRowError(row.rowNumber(), barcode, "条码格式非法: " + barcode));
            return;
        }
        if (validated.containsKey(barcode)) {
            errors.add(new ImportRowError(row.rowNumber(), barcode, "条码在文件中重复: " + barcode));
            return;
        }
        BigDecimal quantity = parseQuantity(row.quantity());
        if (quantity == null) {
            errors.add(new ImportRowError(row.rowNumber(), barcode, "库存数量无法解析: " + row.quantity()));
            return;
        }
        if (quantity.signum() < 0) {
            errors.add(new ImportRowError(row.rowNumber(), barcode, "库存数量不能为负: " + row.quantity()));
            return;
        }
        if (quantity.scale() > QUANTITY_SCALE) {
            errors.add(new ImportRowError(row.rowNumber(), barcode, "库存数量小数位不能超过3位: " + row.quantity()));
            return;
        }
        validated.put(barcode, new ValidatedRow(row, quantity));
    }

    private BigDecimal parseQuantity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ParsedImportFile parseFile(byte[] content, String fileName) {
        try {
            return fileParser.parse(content, fileName);
        } catch (ImportFileFormatException exception) {
            throw new InvalidImportFileException(exception.getMessage());
        }
    }

    private ImportBatchResult saveFailed(
            long storeId,
            String fileName,
            String fileHash,
            LocalDate dataDate,
            List<ParsedImportRow> rows,
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
        long batchId = importRepository.saveFailedBatch(
                new ImportFailure(storeId, fileName, fileHash, dataDate, failureRows, summary));
        return new ImportBatchResult(
                batchId,
                ImportBatchResult.STATUS_FAILED,
                rows.size(),
                0,
                errors.size(),
                List.copyOf(errors));
    }

    private ImportBatchResult post(
            long storeId,
            String fileName,
            String fileHash,
            LocalDate dataDate,
            Long warehouseId,
            List<ParsedImportRow> rows,
            Map<String, ValidatedRow> validated,
            Map<String, Long> productIds) {
        List<ImportPostRow> postRows = validated.values().stream()
                .filter(value -> value.quantity().signum() > 0)
                .map(value -> new ImportPostRow(
                        value.row().rowNumber(),
                        value.row().barcode(),
                        productIds.get(value.row().barcode()),
                        value.quantity()))
                .toList();
        long batchId = importRepository.postBatch(
                new ImportPosting(storeId, fileName, fileHash, dataDate, warehouseId, rows, postRows));
        return new ImportBatchResult(
                batchId,
                ImportBatchResult.STATUS_POSTED,
                rows.size(),
                rows.size(),
                0,
                List.of());
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

    private void requirePositive(long value, String label) {
        if (value <= 0) {
            throw new InvalidImportFileException(label + "必须大于0");
        }
    }

    private record ValidatedRow(ParsedImportRow row, BigDecimal quantity) {
    }
}
