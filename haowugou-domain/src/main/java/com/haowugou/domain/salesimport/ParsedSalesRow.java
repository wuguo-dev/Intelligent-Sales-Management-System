package com.haowugou.domain.salesimport;

/**
 * 解析器产出的一条销售汇总原始行，全部为文本，业务解析与校验由应用层完成。
 *
 * @param rowNumber       Excel 实际行号（表头为第 1 行，数据从第 2 行起）
 * @param barcode         条码
 * @param productName     商品名称，未知条码建待完善商品时使用
 * @param salesQuantity   本期销售数量，可为负（退货）
 * @param salesAmount     本期销售收入，可为负（退货）
 * @param grossProfitRate POS 销售毛利率百分数，用于推算毛利额并原样留档核对
 * @param taxCostPrice    当前机构最后进价，仅作待完善商品的初值提示
 * @param salePrice       当前机构售价，仅作待完善商品的初值提示
 * @param categoryName    品类名称，按名称匹配现有品类
 * @param supplierName    供应商名称，按名称匹配现有供应商
 * @param rawData         整行所有列的文本 JSON，用于审计
 */
public record ParsedSalesRow(
        long rowNumber,
        String barcode,
        String productName,
        String salesQuantity,
        String salesAmount,
        String grossProfitRate,
        String taxCostPrice,
        String salePrice,
        String categoryName,
        String supplierName,
        String rawData) {
}