package com.haowugou.infrastructure.fileimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.salesimport.ParsedSalesFile;
import com.haowugou.domain.salesimport.ParsedSalesRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PosDailySalesExcelFileParserTest {

    /** 实测 POS《商品销售汇总》导出的 15 列表头，含「本期|」「同期|」前缀。 */
    private static final List<String> POS_HEADERS = List.of(
            "条码", "商品名称", "本期|销售数量", "选中机构库存数量", "当前机构最后进价",
            "当前机构售价", "销售毛利率", "本期|销售收入", "销售占比", "日均销售",
            "同期|销售收入", "同期|销售毛利率", "同期|销售数量", "品类名称", "供应商名称");

    private final PosDailySalesExcelFileParser parser =
            new PosDailySalesExcelFileParser(new ObjectMapper());

    @Test
    void parsesRealPosLayoutByHeaderName() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, POS_HEADERS);
            data(sheet, 1, List.of("9556155017024", "130g花王香皂", "6", "20", "4.1",
                    "5.5", "25.45", "33", "0.45", "0.2",
                    "30", "24", "5", "香皂", "天和日化"));
            data(sheet, 2, List.of("0000001234567", "散称绿豆", "0", "10", "3",
                    "4", "", "0", "0", "0",
                    "", "", "0", "粮油", "粮油商行"));
        });

        ParsedSalesFile parsed = parser.parse(content, "商品销售汇总.xls");

        assertEquals(2, parsed.rows().size());
        ParsedSalesRow first = parsed.rows().getFirst();
        assertEquals(2, first.rowNumber());
        assertEquals("9556155017024", first.barcode());
        assertEquals("130g花王香皂", first.productName());
        assertEquals("6", first.salesQuantity());
        assertEquals("33", first.salesAmount());
        assertEquals("25.45", first.grossProfitRate());
        assertEquals("4.1", first.taxCostPrice());
        assertEquals("5.5", first.salePrice());
        assertEquals("香皂", first.categoryName());
        assertEquals("天和日化", first.supplierName());
        assertTrue(first.rawData().contains("\"条码\":\"9556155017024\""));
        assertTrue(first.rawData().contains("\"本期|销售数量\":\"6\""));
        assertEquals(3, parsed.rows().get(1).rowNumber());
        assertEquals("", parsed.rows().get(1).grossProfitRate());
    }

    @Test
    void skipsTotalsRowThatHasNoBarcodeOrProductName() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, POS_HEADERS);
            data(sheet, 1, List.of("6901234567890", "洗发水", "2", "", "", "", "", "10"));
            // 实测末行合计：条码与商品名称皆空，数量与收入有值
            data(sheet, 2, List.of("", "", "988.00", "", "", "", "", "7342.00"));
        });

        ParsedSalesFile parsed = parser.parse(content, "with-totals.xls");

        assertEquals(List.of("6901234567890"),
                parsed.rows().stream().map(ParsedSalesRow::barcode).toList());
    }

    @Test
    void keepsRowWithProductNameButNoBarcodeSoTheErrorIsReportedPerRow() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, POS_HEADERS);
            data(sheet, 1, List.of("", "缺条码商品", "1", "", "", "", "", "5"));
        });

        ParsedSalesFile parsed = parser.parse(content, "missing-barcode.xls");

        assertEquals(1, parsed.rows().size());
        assertEquals("", parsed.rows().getFirst().barcode());
        assertEquals("缺条码商品", parsed.rows().getFirst().productName());
    }

    @Test
    void locatesColumnsByHeaderNameRegardlessOfColumnOrder() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, List.of("销售收入", "供应商名称", "条码", "销售数量", "商品名称"));
            data(sheet, 1, List.of("18.60", "日化供应商", "6901234567890", "3", "牙膏"));
        });

        ParsedSalesFile parsed = parser.parse(content, "reordered.xls");

        ParsedSalesRow row = parsed.rows().getFirst();
        assertEquals("6901234567890", row.barcode());
        assertEquals("3", row.salesQuantity());
        assertEquals("18.60", row.salesAmount());
        assertEquals("日化供应商", row.supplierName());
        assertEquals("", row.grossProfitRate()); // 表头无毛利率列
        assertEquals("", row.categoryName());
    }

    @Test
    void preservesRowNumbersAcrossSkippedRows() throws IOException {
        byte[] content = workbook(false, sheet -> {
            header(sheet, POS_HEADERS);
            data(sheet, 1, List.of("B1", "甲", "1", "", "", "", "", "2"));
            blank(sheet, 2);
            data(sheet, 3, List.of("", "", "", "", "", "", "", ""));
            data(sheet, 4, List.of("B2", "乙", "2", "", "", "", "", "4"));
        });

        ParsedSalesFile parsed = parser.parse(content, "blanks.xls");

        assertEquals(List.of(2L, 5L),
                parsed.rows().stream().map(ParsedSalesRow::rowNumber).toList());
        assertEquals(List.of("B1", "B2"),
                parsed.rows().stream().map(ParsedSalesRow::barcode).toList());
    }

    @Test
    void parsesXlsxAndReadsNumericCellsAsText() throws IOException {
        byte[] content = workbook(true, sheet -> {
            header(sheet, POS_HEADERS);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1234567);
            row.createCell(1).setCellValue("数字商品");
            row.createCell(2).setCellValue(2.5);
            row.createCell(7).setCellValue(12.75);
        });

        ParsedSalesFile parsed = parser.parse(content, "numeric.xlsx");

        ParsedSalesRow row = parsed.rows().getFirst();
        assertTrue(row.barcode().matches("\\d+"), "数字单元格条码应转为纯数字文本: " + row.barcode());
        assertTrue(row.salesQuantity().matches("\\d+(\\.\\d+)?"),
                "数字单元格数量应可解析: " + row.salesQuantity());
        assertTrue(row.salesAmount().matches("\\d+(\\.\\d+)?"),
                "数字单元格收入应可解析: " + row.salesAmount());
    }

    @Test
    void rejectsHeaderMissingRequiredColumns() throws IOException {
        byte[] missingBarcode = workbook(false,
                sheet -> header(sheet, List.of("商品名称", "销售数量", "销售收入")));
        byte[] missingAmount = workbook(false,
                sheet -> header(sheet, List.of("条码", "商品名称", "销售数量")));

        assertTrue(assertThrows(ImportFileFormatException.class,
                () -> parser.parse(missingBarcode, "missing.xls")).getMessage().contains("条码"));
        assertTrue(assertThrows(ImportFileFormatException.class,
                () -> parser.parse(missingAmount, "missing.xls")).getMessage().contains("销售收入"));
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
            filler.fill(workbook.createSheet("商品销售汇总"));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    @FunctionalInterface
    private interface SheetFiller {

        void fill(Sheet sheet);
    }
}
