package com.haowugou.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haowugou.infrastructure.persistence.data.StoreDailySalesDataObject;
import org.apache.ibatis.annotations.Mapper;

/** 门店日销售表的 MyBatis Mapper。 */
@Mapper
public interface StoreDailySalesMapper extends BaseMapper<StoreDailySalesDataObject> {
}
