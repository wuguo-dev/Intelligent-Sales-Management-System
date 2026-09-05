package com.haowugou.infrastructure.persistence.user;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 登录账号表的原始 MyBatis Mapper。
 *
 * <p>只有「按登录名/主键查启用账号」与「回写最近登录时间」两类语句。账号的增删改
 * 由开发人员直接操作数据库，不在这里开口子。
 */
@Mapper
public interface AppUserMapper {

    /** 按登录名查启用账号；不存在或已停用时返回 null。 */
    AppUserRow findActiveByUsername(@Param("username") String username);

    /** 按主键查启用账号；不存在或已停用时返回 null。 */
    AppUserRow findActiveById(@Param("id") long id);

    /** 回写最近登录时间。 */
    int updateLastLoginAt(@Param("id") long id, @Param("loginAt") LocalDateTime loginAt);
}
