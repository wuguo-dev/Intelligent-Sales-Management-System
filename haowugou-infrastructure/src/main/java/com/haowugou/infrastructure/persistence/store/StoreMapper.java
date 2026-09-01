package com.haowugou.infrastructure.persistence.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haowugou.infrastructure.persistence.store.StoreDataObject;
import org.apache.ibatis.annotations.Mapper;

/** 门店表的 MyBatis Mapper。 */
@Mapper
public interface StoreMapper extends BaseMapper<StoreDataObject> {
}
