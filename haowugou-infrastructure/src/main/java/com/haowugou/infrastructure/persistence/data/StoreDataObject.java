package com.haowugou.infrastructure.persistence.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** {@code store} 表的持久化对象，仅在基础设施层使用。 */
@Getter
@Setter
@TableName("store")
public class StoreDataObject {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String storeCode;
    private String storeName;
    private Boolean isActive;
}
