package com.haowugou.infrastructure.fileimport;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.salesimport.DailySalesFileParser;
import com.haowugou.domain.salesimport.ParsedSalesFile;
import com.haowugou.domain.salesimport.ParsedSalesRow;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * POS《商品销售汇总》工作簿（.xls / .xlsx）的解析实现。
 *
 * <p>实测 POS 导出为 15 列，全部单元格为文本类型，表头在第 1 行，末尾带一行合计行
 * （条码与商品名称皆空但数量与收入有值）。本实现按表头名称定位列（不依赖列序）：
 * 「条码」「商品名称」「销售数量」「销售收入」为必需列，缺任一列视为文件格式错误；
 * 其余列缺失时对应字段为空。POS 的期间列名带「本期|」前缀，同时接受不带前缀的写法。
 *
 * <p>条码与商品名称同时为空的行按合计行/空行丢弃——它们不是数据行。
 */
@Component
public class PosDailySalesExcelFileParser implements DailySalesFileParser {

    static final List<String> BARCODE_HEADERS = List.of("条码");
    static final List<String> PRODUCT_NAME_HEADERS = List.of("商品名称");
    static final List<String> QUANTITY_HEADERS = List.of("本期|销售数量", "销售数量");
    static final List<String> AMOUNT_HEADERS = List.of("本期|销售收入", "销售收入");
    static final List<String> RATE_HEADERS = List.of("销售毛利率", "本期|销售毛利率");
    static final List<String> COST_PRICE_HEADERS = List.of("当前机构最后进价", "最后进价");
    static final List<String> SALE_PRICE_HEADERS = List.of("当前机构售价", "售价");
    static final List<String> CATEGORY_HEADERS = List.of("品类名称");
    static final List<String> SUPPLIER_HEADERS = List.of("供应商名称");

    private static final int COLUMN_ABSENT = -1;

    private final ObjectMapper objectMapper;

    public PosDailySalesExcelFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ParsedSalesFile parse(byte[] content, String fileName) {
        List<SheetRow> rows = new ArrayList<>();
        try {
            EasyExcel.read(new ByteArrayInputStream(content))
                    .registerReadListener(new AnalysisEventListener<Map<Integer, String>>() {
                        @Override
                        public void invoke(Map<Integer, String> row, AnalysisContext context) {
                            rows.add(new SheetRow(
                                    context.readRowHolder().getRowIndex() + 1, row));
                        }

                        @Override
                        public void doAfterAllAnalysed(AnalysisContext context) {
                            // 无需处理
                        }
                    })
                    .sheet()
                    .headRowNumber(0)
                    .doRead();
        } catch (RuntimeException exception) {
            throw new ImportFileFormatException("Excel 文件解析失败: " + fileName);
        }
        return toParsedFile(rows);
    }

    private ParsedSalesFile toParsedFile(List<SheetRow> rows) {
        if (rows.isEmpty()) {
            throw new ImportFileFormatException("Excel 文件中没有内容");
        }
        SheetRow headerRow = rows.get(0);
        int barcodeColumn = requireColumn(headerRow, BARCODE_HEADERS);
        int productNameColumn = requireColumn(headerRow, PRODUCT_NAME_HEADERS);
        int quantityColumn = requireColumn(headerRow, QUANTITY_HEADERS);
        int amountColumn = requireColumn(headerRow, AMOUNT_HEADERS);
        int rateColumn = optionalColumn(headerRow, RATE_HEADERS);
        int costPriceColumn = optionalColumn(headerRow, COST_PRICE_HEADERS);
        int salePriceColumn = optionalColumn(headerRow, SALE_PRICE_HEADERS);
        int categoryColumn = optionalColumn(headerRow, CATEGORY_HEADERS);
        int supplierColumn = optionalColumn(headerRow, SUPPLIER_HEADERS);
        Map<Integer, String> headerByColumn = headerByName(headerRow);

        List<ParsedSalesRow> parsedRows = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            SheetRow sheetRow = rows.get(index);
            String barcode = cell(sheetRow, barcodeColumn);
            String productName = cell(sheetRow, productNameColumn);
            // 合计行与空行：没有商品标识，不是数据行
            if (barcode.isEmpty() && productName.isEmpty()) {
                continue;
            }
            parsedRows.add(new ParsedSalesRow(
                    sheetRow.rowNumber(),
                    barcode,
                    productName,
                    cell(sheetRow, quantityColumn),
                    cell(sheetRow, amountColumn),
                    cell(sheetRow, rateColumn),
                    cell(sheetRow, costPriceColumn),
                    cell(sheetRow, salePriceColumn),
                    cell(sheetRow, categoryColumn),
                    cell(sheetRow, supplierColumn),
                    toRawDataJson(sheetRow, headerByColumn)));
        }
        return new ParsedSalesFile(List.copyOf(parsedRows));
    }

    private int requireColumn(SheetRow headerRow, List<String> headerNames) {
        int column = optionalColumn(headerRow, headerNames);
        if (column == COLUMN_ABSENT) {
            throw new ImportFileFormatException("表头缺少「" + headerNames.getFirst() + "」列");
        }
        return column;
    }

    private int optionalColumn(SheetRow headerRow, List<String> headerNames) {
        for (String headerName : headerNames) {
            for (Map.Entry<Integer, String> entry : headerRow.cells().entrySet()) {
                if (headerName.equals(entry.getValue() == null ? "" : entry.getValue().trim())) {
                    return entry.getKey();
                }
            }
        }
        return COLUMN_ABSENT;
    }

    private Map<Integer, String> headerByName(SheetRow headerRow) {
        Map<Integer, String> header = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : headerRow.cells().entrySet()) {
            header.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().trim());
        }
        return header;
    }

    private String cell(SheetRow row, int column) {
        if (column == COLUMN_ABSENT) {
            return "";
        }
        String value = row.cells().get(column);
        return value == null ? "" : value.trim();
    }

    private String toRawDataJson(SheetRow row, Map<Integer, String> headerByColumn) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : row.cells().entrySet()) {
            raw.put(headerByColumn.getOrDefault(entry.getKey(), "列" + entry.getKey()),
                    entry.getValue() == null ? "" : entry.getValue());
        }
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException exception) {
            throw new ImportFileFormatException("原始行序列化失败: " + row.rowNumber());
        }
    }

    /** 一行工作簿内容：Excel 实际行号（1 起）与列索引 → 文本值。 */
    private record SheetRow(long rowNumber, Map<Integer, String> cells) {
    }
}
