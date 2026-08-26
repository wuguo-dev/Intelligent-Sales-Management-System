package com.haowugou.application.product;

/** 门店商品查询参数不符合应用层约束。 */
public final class InvalidStoreProductQueryException extends RuntimeException {

    public InvalidStoreProductQueryException(String message) {
        super(message);
    }
}
