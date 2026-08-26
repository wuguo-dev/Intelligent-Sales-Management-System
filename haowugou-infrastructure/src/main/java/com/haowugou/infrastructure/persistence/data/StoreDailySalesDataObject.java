package com.haowugou.infrastructure.persistence.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** {@code store_daily_sales} 表的持久化对象，仅在基础设施层使用。 */
@Getter
@Setter
@TableName("store_daily_sales")
public class StoreDailySalesDataObject {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private LocalDate businessDate;
    private BigDecimal totalSalesAmount;
    private Integer orderCount;
    private BigDecimal refundAmount;
    private BigDecimal grossProfitAmount;
    private String dataOrigin;
}
