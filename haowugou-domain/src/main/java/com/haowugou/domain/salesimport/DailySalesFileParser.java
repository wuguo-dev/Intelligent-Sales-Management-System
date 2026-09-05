package com.haowugou.domain.salesimport;

import com.haowugou.domain.importbatch.ImportFileFormatException;

/**
 * 每日销售汇总文件的解析边界：POS《商品销售汇总》导出格式到内部行模型的转换。
 *
 * <p>实现方负责识别 POS 字段布局（表头名称、文本单元格、末尾合计行等），业务校验由应用层完成。
 */
public interface DailySalesFileParser {

    /**
     * 解析工作簿内容。
     *
     * @param content  文件字节（.xls 或 .xlsx）
     * @param fileName 原始文件名，仅用于错误信息
     * @return 按 Excel 行序排列的数据行（空行与合计行已剔除）
     * @throws ImportFileFormatException 无法解析、表头缺必需列、或没有数据行
     */
    ParsedSalesFile parse(byte[] content, String fileName);
}