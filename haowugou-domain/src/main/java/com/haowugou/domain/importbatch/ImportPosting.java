package com.haowugou.domain.importbatch;

import java.time.LocalDate;
import java.util.List;

/**
 * 校验通过的初始库存过账载荷。
 *
 * @param storeId     门店主键，批次、库存与流水均按此隔离
 * @param fileName    原始文件名
 * @param fileHash    文件 SHA-256 指纹（十六进制小写）
 * @param dataDate    数据归属日期（导入当天），同时作为流水业务日期
 * @param warehouseId 可选的仓库分配；为 null 表示待分配
 * @param rawRows     全部数据行（parse_status 记 VALID）
 * @param postRows    数量大于 0、需要累加库存并产生流水的行
 */
public record ImportPosting(
        long storeId,
        String fileName,
        String fileHash,
        LocalDate dataDate,
        Long warehouseId,
        List<ParsedImportRow> rawRows,
        List<ImportPostRow> postRows) {
}
