package com.haowugou.infrastructure.persistence.user;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code app_user} 一行的查询载体。
 *
 * <p>{@code roleId} 用 int 而不是枚举：解析成 {@link com.haowugou.domain.user.UserRole}
 * 是适配器的职责，Mapper 只负责把列取出来。
 *
 * <p>{@code storeId} 是包装类型——管理员这一列为 NULL，用 long 会被拆箱成 0，
 * 让「不绑门店」变成「绑了 0 号门店」。
 */
@Getter
@Setter
public class AppUserRow {

    private long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private int roleId;
    private Long storeId;
}
