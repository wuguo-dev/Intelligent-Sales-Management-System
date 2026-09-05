package com.haowugou.domain.importbatch;

/**
 * 导入文件的解析边界：负责 POS 外部文件格式到内部行模型的转换。
 *
 * <p>实现方需要了解 POS 字段布局（表头名称、文本单元格等），业务校验由应用层完成。
 */
public interface ImportFileParser {

    /**
     * 解析工作簿内容。
     *
     * @param content  文件字节（.xls 或 .xlsx）
     * @param fileName 原始文件名，仅用于错误信息
     * @return 按 Excel 行序排列的数据行（空行已剔除）
     * @throws ImportFileFormatException 无法解析、表头缺「条码」或「库存数量」、没有数据行
     */
    ParsedImportFile parse(byte[] content, String fileName);
}
