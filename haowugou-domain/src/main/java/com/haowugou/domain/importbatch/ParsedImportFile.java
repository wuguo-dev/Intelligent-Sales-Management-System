package com.haowugou.domain.importbatch;

import java.util.List;

/** 解析完成的工作簿：按 Excel 行序排列的数据行（空行已剔除）。 */
public record ParsedImportFile(List<ParsedImportRow> rows) {
}
