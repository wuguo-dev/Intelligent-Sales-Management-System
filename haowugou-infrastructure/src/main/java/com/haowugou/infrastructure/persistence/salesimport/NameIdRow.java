package com.haowugou.infrastructure.persistence.salesimport;

import lombok.Getter;
import lombok.Setter;

/** 按名称查主数据主键的查询投影（品类、供应商共用）。 */
@Getter
@Setter
public class NameIdRow {

    private String name;
    private Long id;
}
