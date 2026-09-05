package com.haowugou.domain.salesimport;

import java.util.List;

/** 解析完成的销售汇总工作簿：按 Excel 行序排列的数据行（空行与合计行已剔除）。 */
public record ParsedSalesFile(List<ParsedSalesRow> rows) {
}