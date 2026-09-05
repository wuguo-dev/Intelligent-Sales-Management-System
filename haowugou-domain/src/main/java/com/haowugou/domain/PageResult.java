package com.haowugou.domain.pagination;

import java.util.List;

/** 与具体持久化框架无关的分页查询结果。 */
public record PageResult<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public PageResult {
        items = List.copyOf(items);
    }
}
