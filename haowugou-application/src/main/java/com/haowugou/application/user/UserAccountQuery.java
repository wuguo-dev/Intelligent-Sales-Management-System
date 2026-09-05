package com.haowugou.application.user;

import com.haowugou.application.user.exception.InvalidUserQueryException;
import com.haowugou.application.user.exception.UserAccountNotFoundException;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 账号查询用例：认证取账号、登录后回写时间、按登录态输出身份视图。
 *
 * <p>本用例不做密码比对——哈希算法属于框架能力，放在 bootstrap 的
 * {@code PasswordEncoder} 里。这里只负责「按登录名取出启用账号」，
 * 让应用层不依赖 Spring Security。
 *
 * <p>{@code loadForAuthentication} 与 {@code findProfile} 的失败口径不同是有意的：
 * 前者由认证过滤器调用，账号缺失是正常的登录失败，返回 {@link Optional} 由框架转成 401；
 * 后者由已登录请求调用，此时账号却查不到（登录后被停用或删除），属于异常情况，抛 404。
 */
public final class UserAccountQuery {

    /** 对应 {@code app_user.username} 的列长度。 */
    static final int MAX_USERNAME_LENGTH = 64;

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;

    public UserAccountQuery(UserRepository userRepository, StoreRepository storeRepository) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.storeRepository = Objects.requireNonNull(storeRepository);
    }

    /**
     * 供认证使用：按登录名取启用账号，含密码哈希。
     *
     * <p>登录名区分大小写，不做大小写归一——数据库唯一键用的是 {@code utf8mb4_bin}，
     * 在这里归一会让「库里能同时存在 Admin 与 admin」但登录时只命中一个。
     */
    public Optional<AppUser> loadForAuthentication(String username) {
        if (username == null || username.isBlank() || username.length() > MAX_USERNAME_LENGTH) {
            // 不合法的登录名不查库：这类输入不可能匹配到账号，直接当认证失败。
            return Optional.empty();
        }
        return userRepository.findActiveByUsername(username.strip());
    }

    /**
     * 查已登录账号的身份视图。
     *
     * @throws UserAccountNotFoundException 账号已被停用或删除（会话仍在，但账号没了）
     */
    public UserProfileResult findProfile(long userId) {
        requirePositive(userId, "账号ID");
        AppUser user = userRepository.findActiveById(userId)
                .orElseThrow(() -> new UserAccountNotFoundException(userId));
        return toProfile(user);
    }

    /** 把账号转成身份视图；普通用户补上绑定门店的名称。 */
    public UserProfileResult toProfile(AppUser user) {
        Objects.requireNonNull(user, "账号不能为空");
        Store store = user.storeId() == null
                ? null
                // 门店查不到时留 null 而不是抛异常：外键保证门店存在，
                // 但门店可能被停用，此时不该把已登录用户彻底锁在门外。
                : storeRepository.findActiveById(user.storeId()).orElse(null);
        return new UserProfileResult(
                user.id(),
                user.username(),
                user.displayName(),
                user.role(),
                store);
    }

    /**
     * 记录登录时间。
     *
     * <p>审计信息，写失败不该让已经通过认证的登录变成失败，所以异常在此吞掉。
     */
    public void recordLogin(long userId, LocalDateTime loginAt) {
        requirePositive(userId, "账号ID");
        Objects.requireNonNull(loginAt, "登录时间不能为空");
        try {
            userRepository.touchLastLogin(userId, loginAt);
        } catch (RuntimeException ignored) {
            // 有意忽略：登录已成功，最近登录时间只是审计字段。
        }
    }

    private void requirePositive(long value, String label) {
        if (value <= 0) {
            throw new InvalidUserQueryException(label + "必须大于0");
        }
    }
}
