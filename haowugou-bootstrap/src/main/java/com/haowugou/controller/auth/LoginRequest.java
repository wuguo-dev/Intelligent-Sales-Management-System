package com.haowugou.controller.auth;

/**
 * 登录请求体。
 *
 * <p>没有校验注解：空登录名或空密码由认证流程判定为失败（401），
 * 在这里额外报 400 会让「参数不合法」与「凭据不正确」两种失败对外可区分。
 */
public record LoginRequest(String username, String password) {
}
