-- =============================================================================
-- 迁移：新增系统登录账号表 app_user，支撑管理员 / 普通用户两级权限
--
-- 背景：此前所有 /api/** 接口无认证，任何人可读全部门店数据、可发起导入与撤销。
-- 本次引入登录态：账号由开发人员直接写库（不做注册接口），按 role_id 区分角色，
-- 管理员可用全部功能，普通用户只能查看商品售价、库存数量与所处仓库。
--
-- 表名用 app_user 而不是 user：user 是 MySQL 系统表名（mysql.user），
-- 同名表在很多客户端与运维脚本里需要反引号，容易出错。
--
-- 权限口径做成数据库不变量（与 import_batch 用生成列表达业务不变量同思路）：
-- chk_app_user_store_scope 强制「管理员不绑门店、普通用户必须绑门店」。
-- 手工建账号时填错立刻报错，而不是运行期出现一个能看全部门店的「普通用户」。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS 幂等；末尾种子账号的 INSERT 用
-- ON DUPLICATE KEY UPDATE 保持幂等（重复执行会把密码重置回下方明文）。
-- 影响：新表，不改动既有表结构与数据。
--
-- 本文件是 UTF-8。Windows 版 mysql 客户端默认 character_set_client=gbk，
-- 不声明字符集直接 `mysql < 本文件` 会把中文按 gbk 解码：列注释变乱码、
-- 中文 display_name 报 ERROR 1406 Data too long。下面的 SET NAMES 就是防这个，
-- 命令行另加 --default-character-set=utf8mb4 更稳妥。
-- =============================================================================

SET NAMES utf8mb4;

USE `haowugou`;

CREATE TABLE IF NOT EXISTS `app_user`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '账号主键',
    `username`      VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '登录名，区分大小写',
    `password_hash` VARCHAR(100)    NOT NULL COMMENT 'BCrypt 哈希；留长以便未来换算法',
    `display_name`  VARCHAR(64)     NOT NULL COMMENT '展示名',
    `role_id`       TINYINT UNSIGNED NOT NULL COMMENT '1=ADMIN 管理员，2=USER 普通用户',
    `store_id`      BIGINT UNSIGNED NULL COMMENT '普通用户绑定门店；管理员为 NULL 代表全部门店',
    `is_active`     TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `last_login_at` DATETIME(3)     NULL COMMENT '最近登录时间',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_user_username` (`username`),
    KEY `idx_app_user_store` (`store_id`),
    CONSTRAINT `fk_app_user_store`
        FOREIGN KEY (`store_id`) REFERENCES `store` (`id`)
            ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_app_user_role`
        CHECK (`role_id` IN (1, 2)),
    CONSTRAINT `chk_app_user_active`
        CHECK (`is_active` IN (0, 1)),
    CONSTRAINT `chk_app_user_store_scope`
        CHECK (
            (`role_id` = 1 AND `store_id` IS NULL)
                OR (`role_id` = 2 AND `store_id` IS NOT NULL)
            )
) ENGINE = InnoDB COMMENT = '系统登录账号';

-- -----------------------------------------------------------------------------
-- 种子账号
--
-- 明文密码（仅本地开发环境使用，部署前必须替换）：
--   admin      / Admin@123     管理员，可用全部功能
--   store1user / Store1@123    普通用户，绑定 id = 1 的门店
--
-- 下方哈希由 BCryptPasswordEncoder（strength 10）生成。AppUserSeedPasswordTest 直接读本文件、
-- 断言哈希与上述明文匹配，防止粘错导致谁都登不进去——它不连数据库，所以每次 mvn test 都会跑。
-- AppUserSeedIntegrationTest 另外验库里的行（迁移是否执行过、能否按真实 Mapper 读出来），
-- 但它依赖真实 MySQL，且下面这条条件 INSERT 在空库上根本不插行，验不到 store1user。
--
-- 普通用户的 store_id 取「第一个已启用门店」而不是硬编码 1：全新库的门店主键
-- 未必从 1 开始。若库里还没有任何启用门店，该 INSERT 不会插入任何行
-- （chk_app_user_store_scope 不允许普通用户 store_id 为 NULL）——门店导入之后重跑本脚本即可补上。
-- -----------------------------------------------------------------------------
INSERT INTO `app_user` (`username`, `password_hash`, `display_name`, `role_id`, `store_id`)
VALUES ('admin',
        '$2a$10$f2BIX/9aPOAIL58RPrNzvecKrreO9ZAXgtSapMle20HHrXcAn4gy2',
        '系统管理员',
        1,
        NULL)
ON DUPLICATE KEY UPDATE `password_hash` = VALUES(`password_hash`),
                        `display_name`  = VALUES(`display_name`),
                        `role_id`       = VALUES(`role_id`),
                        `store_id`      = VALUES(`store_id`),
                        `is_active`     = 1;

INSERT INTO `app_user` (`username`, `password_hash`, `display_name`, `role_id`, `store_id`)
SELECT 'store1user',
       '$2a$10$YD6D5yR.Bwby9YldjNgZFuyp/St4OxVD6/U/CZvk8G4VmaehLbQEu',
       '门店查询员',
       2,
       `s`.`id`
FROM `store` AS `s`
WHERE `s`.`is_active` = 1
ORDER BY `s`.`id`
LIMIT 1
ON DUPLICATE KEY UPDATE `password_hash` = VALUES(`password_hash`),
                        `display_name`  = VALUES(`display_name`),
                        `role_id`       = VALUES(`role_id`),
                        `store_id`      = VALUES(`store_id`),
                        `is_active`     = 1;
