package com.haowugou.infrastructure.fileimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.importbatch.ParsedImportFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PosProductExcelFileParserTest {

    private static final List<String> POS_HEADERS = List.of(
            "商品名称", "条码", "单位", "供应商名称", "含税成本价", "售价",
            "毛利率", "品类编码", "品类名称", "库存数量", "商品备注", "提成率/固定值");

    private final PosProductExcelFileParser parser = new PosProductExcelFileParser(new ObjectMapper());

    @Test
    void parsesRealPosLayoutByHeaderName() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, POS_HEADERS);
            data(sheet, 1, List.of("130g花王香皂", "9556155017024", "块", "天和日化", "4", "5",
                    "20", "010204", "香皂", "20", "", "0%"));
            data(sheet, 2, List.of("散称绿豆", "0000001234567", "kg", "粮油商行", "3", "4",
                    "25", "010101", "粮油", "0.5", "", "0%"));
            blank(sheet, 3);
        });

        ParsedImportFile parsed = parser.parse(content, "商品资料1.xls");

        assertEquals(2, parsed.rows().size());
        assertEquals(2, parsed.rows().get(0).rowNumber());
        assertEquals("9556155017024", parsed.rows().get(0).barcode());
        assertEquals("20", parsed.rows().get(0).quantity());
        assertTrue(parsed.rows().get(0).rawData().contains("\"条码\":\"9556155017024\""));
        assertTrue(parsed.rows().get(0).rawData().contains("\"商品名称\":\"130g花王香皂\""));
        assertEquals(3, parsed.rows().get(1).rowNumber());
        assertEquals("0000001234567", parsed.rows().get(1).barcode());
        assertEquals("0.5", parsed.rows().get(1).quantity());
    }

    @Test
    void locatesColumnsByHeaderNameRegardlessOfColumnOrder() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, List.of("库存数量", "商品名称", "条码"));
            data(sheet, 1, List.of("7", "洗发水", "6901234567890"));
        });

        ParsedImportFile parsed = parser.parse(content, "reordered.xls");

        assertEquals("6901234567890", parsed.rows().getFirst().barcode());
        assertEquals("7", parsed.rows().getFirst().quantity());
    }

    @Test
    void preservesRowNumbersAcrossSkippedBlankRows() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, POS_HEADERS);
            data(sheet, 1, List.of("甲", "B1", "件", "", "", "", "", "", "", "1", "", ""));
            blank(sheet, 2);
            blank(sheet, 3);
            data(sheet, 4, List.of("乙", "B2", "件", "", "", "", "", "", "", "2", "", ""));
        });

        ParsedImportFile parsed = parser.parse(content, "blanks.xls");

        assertEquals(List.of(2L, 5L),
                parsed.rows().stream().map(row -> row.rowNumber()).toList());
        assertEquals(List.of("B1", "B2"),
                parsed.rows().stream().map(row -> row.barcode()).toList());
    }

    @Test
    void parsesXlsxAndReadsNumericCellsAsText() throws IOException {
        byte[] content = workbook(true, sheet -> {
            header(sheet, POS_HEADERS);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("数字商品");
            Cell numericBarcode = row.createCell(1);
            numericBarcode.setCellValue(1234567);
            row.createCell(9).setCellValue(20.5);
        });

        ParsedImportFile parsed = parser.parse(content, "numeric.xlsx");

        assertEquals(1, parsed.rows().size());
        assertTrue(parsed.rows().getFirst().barcode().matches("\\d+"),
                "数字单元格条码应转为纯数字文本: " + parsed.rows().getFirst().barcode());
        assertTrue(parsed.rows().getFirst().quantity().matches("\\d+(\\.\\d+)?"),
                "数字单元格数量应可解析: " + parsed.rows().getFirst().quantity());
    }

    @Test
    void rejectsMissingHeader() throws IOException {
        byte[] content = workbook(false, sheet -> header(sheet, List.of("商品名称", "单位", "库存数量")));

        ImportFileFormatException exception = assertThrows(ImportFileFormatException.class,
                () -> parser.parse(content, "missing.xls"));
        assertTrue(exception.getMessage().contains("条码"));
    }

    @Test
    void rejectsContentThatIsNotExcel() {
        assertThrows(ImportFileFormatException.class,
                () -> parser.parse("not-an-excel-file".getBytes(), "fake.xls"));
    }

    @Test
    void rejectsEmptyWorkbookWithoutHeaderRow() throws IOException {
        byte[] content = workbook(false, sheet -> {
        });

        assertThrows(ImportFileFormatException.class, () -> parser.parse(content, "empty.xls"));
    }

    private void header(Sheet sheet, List<String> headers) {
        Row row = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            row.createCell(column).setCellValue(headers.get(column));
        }
    }

    private void data(Sheet sheet, int rowNumber, List<String> values) {
        Row row = sheet.createRow(rowNumber);
        for (int column = 0; column < values.size(); column++) {
            row.createCell(column).setCellValue(values.get(column));
        }
    }

    private void blank(Sheet sheet, int rowNumber) {
        sheet.createRow(rowNumber);
    }

    private byte[] workbook(boolean xlsx, SheetFiller filler) throws IOException {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            filler.fill(workbook.createSheet("商品资料"));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    @FunctionalInterface
    private interface SheetFiller {

        void fill(Sheet sheet);
    }
}
