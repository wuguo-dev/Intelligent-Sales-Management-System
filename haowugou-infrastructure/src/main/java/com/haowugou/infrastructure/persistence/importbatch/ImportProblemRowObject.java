package com.haowugou.infrastructure.persistence.importbatch;

import lombok.Getter;
import lombok.Setter;

/**
 * 问题行查询载体：{@code import_raw_row} 中 parse_status 非 VALID 的行。
 *
 * <p>不含 {@code raw_data}：整行 JSON 只用于事后审计，接口返回定位信息即可。
 */
@Getter
@Setter
public class ImportProblemRowObject {

    private long rowNumber;
    private String barcode;
    private String parseStatus;
    private String errorMessage;
}
