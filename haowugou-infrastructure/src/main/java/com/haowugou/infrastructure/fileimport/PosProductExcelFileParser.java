package com.haowugou.infrastructure.fileimport;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.importbatch.ImportFileParser;
import com.haowugou.domain.importbatch.ParsedImportFile;
import com.haowugou.domain.importbatch.ParsedImportRow;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * POS 商品资料工作簿（.xls / .xlsx）的解析实现。
 *
 * <p>实测 POS 导出为 12 列商品资料，全部单元格为文本类型，表头在第 1 行。
 * 本实现按表头名称定位「条码」「库存数量」两列（不依赖列序），其余列仅进入原始行审计 JSON。
 */
@Component
public class PosProductExcelFileParser implements ImportFileParser {

    static final String BARCODE_HEADER = "条码";
    static final String QUANTITY_HEADER = "库存数量";

    private final ObjectMapper objectMapper;

    public PosProductExcelFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ParsedImportFile parse(byte[] content, String fileName) {
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

    private ParsedImportFile toParsedFile(List<SheetRow> rows) {
        if (rows.isEmpty()) {
            throw new ImportFileFormatException("Excel 文件中没有内容");
        }
        SheetRow headerRow = rows.get(0);
        int barcodeColumn = findHeaderColumn(headerRow, BARCODE_HEADER);
        int quantityColumn = findHeaderColumn(headerRow, QUANTITY_HEADER);
        Map<Integer, String> headerByColumn = headerByName(headerRow);

        List<ParsedImportRow> parsedRows = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            SheetRow sheetRow = rows.get(index);
            String barcode = cell(sheetRow, barcodeColumn);
            String quantity = cell(sheetRow, quantityColumn);
            if (barcode.isEmpty() && quantity.isEmpty()) {
                continue;
            }
            parsedRows.add(new ParsedImportRow(
                    sheetRow.rowNumber(), barcode, quantity, toRawDataJson(sheetRow, headerByColumn)));
        }
        return new ParsedImportFile(List.copyOf(parsedRows));
    }

    private int findHeaderColumn(SheetRow headerRow, String headerName) {
        for (Map.Entry<Integer, String> entry : headerRow.cells().entrySet()) {
            if (headerName.equals(entry.getValue() == null ? "" : entry.getValue().trim())) {
                return entry.getKey();
            }
        }
        throw new ImportFileFormatException("表头缺少「" + headerName + "」列");
    }

    private Map<Integer, String> headerByName(SheetRow headerRow) {
        Map<Integer, String> header = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : headerRow.cells().entrySet()) {
            header.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().trim());
        }
        return header;
    }

    private String cell(SheetRow row, int column) {
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
