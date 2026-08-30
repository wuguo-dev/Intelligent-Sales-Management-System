package com.haowugou.application.operating.exception;

/**
 * 经营数据查询参数不符合应用层约束。
 *
 * <p>专用异常使外层 Adapter 只公开可控的参数错误，避免把普通编程异常误报为客户端错误。
 */
public final class InvalidOperatingDataQueryException extends RuntimeException {

    public InvalidOperatingDataQueryException(String message) {
        super(message);
    }
}
