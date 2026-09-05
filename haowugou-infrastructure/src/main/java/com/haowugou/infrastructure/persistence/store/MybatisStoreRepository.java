package com.haowugou.infrastructure.persistence.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.infrastructure.persistence.store.StoreDataObject;
import com.haowugou.infrastructure.persistence.store.StoreMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis 实现领域层门店 Repository 接口的持久化 Adapter。 */
@Repository
public class MybatisStoreRepository implements StoreRepository {

    private final StoreMapper mapper;

    public MybatisStoreRepository(StoreMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Store> findAllActive() {
        return mapper.selectList(Wrappers.<StoreDataObject>lambdaQuery()
                        .eq(StoreDataObject::getIsActive, true)
                        .orderByAsc(StoreDataObject::getId))
                .stream()
                .map(row -> new Store(row.getId(), row.getStoreCode(), row.getStoreName()))
                .toList();
    }

    @Override
    public Optional<Store> findActiveById(long storeId) {
        StoreDataObject row = mapper.selectOne(Wrappers.<StoreDataObject>lambdaQuery()
                .eq(StoreDataObject::getId, storeId)
                .eq(StoreDataObject::getIsActive, true));
        return Optional.ofNullable(row)
                .map(store -> new Store(store.getId(), store.getStoreCode(), store.getStoreName()));
    }

    @Override
    public boolean existsActiveById(long storeId) {
        return mapper.selectCount(Wrappers.<StoreDataObject>lambdaQuery()
                .eq(StoreDataObject::getId, storeId)
                .eq(StoreDataObject::getIsActive, true)) > 0;
    }
}
