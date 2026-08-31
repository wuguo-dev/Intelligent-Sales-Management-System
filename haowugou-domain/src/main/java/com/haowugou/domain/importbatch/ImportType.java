package com.haowugou.domain.importbatch;

/** 导入类型，对应 {@code import_batch.import_type}。 */
public enum ImportType {

    /** 初始库存导入：每店只能有一个有效批次。 */
    INITIAL_INVENTORY,

    /** 每日销售导入：每店每业务日期只能有一个有效批次。 */
    DAILY_SALES
}
