package com.haowugou.application.user.exception;

/**
 * 账号不存在或已停用。
 *
 * <p>两种情况共用一个异常且不在消息里区分：认证失败的具体原因不对外暴露，
 * 否则登录接口会变成账号枚举工具。
 */
public final class UserAccountNotFoundException extends RuntimeException {

    public UserAccountNotFoundException(String username) {
        super("账号不存在或已停用: username=" + username);
    }

    public UserAccountNotFoundException(long userId) {
        super("账号不存在或已停用: userId=" + userId);
    }
}
