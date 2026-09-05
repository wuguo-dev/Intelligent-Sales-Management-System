package com.haowugou.application.user.exception;

/** 账号查询参数非法（登录名为空、超长、主键非正数），对外映射为 400。 */
public final class InvalidUserQueryException extends RuntimeException {

    public InvalidUserQueryException(String message) {
        super(message);
    }
}
