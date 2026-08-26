-- 好物购商品销售分析系统数据库结构
-- 版本：1.0.0
-- 日期：2026-08-26
-- 目标：MySQL 8.0 / 阿里云 RDS MySQL 8.0
--
-- 重要边界：
-- 1. 本文件创建已确认的“仓库定位 + 商品当前库存 + 日商品销售 + 库存流水”目标模型。
-- 2. 当前 main 分支仍有查询 store、store_daily_sales、inventory_snapshot 的代码；
--    这些旧表不在本文件中创建，相关 Java 查询链路需要后续适配。
-- 3. 本文件不会 DROP 数据库或表，可用于全新数据库初始化；它不是旧结构迁移脚本。
-- 4. 批次过账、库存扣减和撤销必须由应用在数据库事务中执行，本文件不使用触发器代替业务事务。

SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE DATABASE IF NOT EXISTS `haowugou`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `haowugou`;

-- -----------------------------------------------------------------------------
-- 1. 仓库：仅用于商品定位，不划分库位
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `warehouse` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '仓库主键',
    `warehouse_code` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库编码',
    `warehouse_name` VARCHAR(128) NOT NULL COMMENT '仓库名称',
    `description` VARCHAR(500) NULL COMMENT '仓库说明',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_warehouse_code` (`warehouse_code`),
    KEY `idx_warehouse_name` (`warehouse_name`),
    CONSTRAINT `chk_warehouse_status`
        CHECK (`status` IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB COMMENT = '仓库主数据';

-- -----------------------------------------------------------------------------
-- 2. 品类
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `category` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '品类主键',
    `category_code` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'POS 品类编码',
    `category_name` VARCHAR(128) NOT NULL COMMENT '品类名称',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_code` (`category_code`),
    KEY `idx_category_name` (`category_name`),
    CONSTRAINT `chk_category_status`
        CHECK (`status` IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB COMMENT = '商品品类主数据';

-- -----------------------------------------------------------------------------
-- 3. 供应商
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `supplier` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '供应商主键',
    `supplier_code` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '供应商编码',
    `supplier_name` VARCHAR(255) NOT NULL COMMENT '供应商名称或 POS 原始标签',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_supplier_code` (`supplier_code`),
    UNIQUE KEY `uk_supplier_name` (`supplier_name`),
    CONSTRAINT `chk_supplier_status`
        CHECK (`status` IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB COMMENT = '供应商主数据';

-- -----------------------------------------------------------------------------
-- 4. 商品：条码是业务唯一键；待完善商品允许暂时不分配仓库
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品主键',
    `barcode` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '商品条码，业务唯一键',
    `product_name` VARCHAR(255) NOT NULL COMMENT '商品名称',
    `unit` VARCHAR(32) NULL COMMENT '计量单位',
    `category_id` BIGINT UNSIGNED NULL COMMENT '品类主键',
    `warehouse_id` BIGINT UNSIGNED NULL COMMENT '商品所在仓库；待完善商品可为空',
    `tax_cost_price` DECIMAL(18, 4) NULL COMMENT '含税成本价',
    `sale_price` DECIMAL(18, 4) NULL COMMENT '当前售价',
    `remarks` VARCHAR(500) NULL COMMENT '商品备注',
    `data_status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE/PENDING/DISABLED',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_barcode` (`barcode`),
    KEY `idx_product_name` (`product_name`),
    KEY `idx_product_warehouse_status` (`warehouse_id`, `data_status`),
    KEY `idx_product_category_status` (`category_id`, `data_status`),
    CONSTRAINT `fk_product_category`
        FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)
        ON UPDATE RESTRICT ON DELETE SET NULL,
    CONSTRAINT `fk_product_warehouse`
        FOREIGN KEY (`warehouse_id`) REFERENCES `warehouse` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_product_data_status`
        CHECK (`data_status` IN ('ACTIVE', 'PENDING', 'DISABLED')),
    CONSTRAINT `chk_product_warehouse_required`
        CHECK (`data_status` = 'PENDING' OR `warehouse_id` IS NOT NULL)
) ENGINE = InnoDB COMMENT = '商品主数据';

-- -----------------------------------------------------------------------------
-- 5. 商品与供应商关联：一个商品可以对应多个供应商
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_supplier` (
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '商品主键',
    `supplier_id` BIGINT UNSIGNED NOT NULL COMMENT '供应商主键',
    `is_primary` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否主要供应商',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`product_id`, `supplier_id`),
    KEY `idx_product_supplier_supplier` (`supplier_id`, `product_id`),
    CONSTRAINT `fk_product_supplier_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_product_supplier_supplier`
        FOREIGN KEY (`supplier_id`) REFERENCES `supplier` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_product_supplier_primary`
        CHECK (`is_primary` IN (0, 1))
) ENGINE = InnoDB COMMENT = '商品供应商关联';

-- -----------------------------------------------------------------------------
-- 6. 商品当前库存：一个商品唯一一行；允许负库存
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_inventory` (
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '商品主键',
    `current_quantity` DECIMAL(18, 3) NOT NULL DEFAULT 0 COMMENT '当前库存，允许负数',
    `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '并发控制版本号',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`product_id`),
    KEY `idx_product_inventory_quantity` (`current_quantity`, `product_id`),
    CONSTRAINT `fk_product_inventory_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE = InnoDB COMMENT = '商品当前库存余额';

-- -----------------------------------------------------------------------------
-- 7. 文件导入批次
--    生成列 + 唯一索引保证：
--    a. 每个销售业务日期最多一个 POSTED 批次；
--    b. 最多一个仍有效的初始库存批次。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `import_batch` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '导入批次主键',
    `import_type` VARCHAR(32) NOT NULL COMMENT 'INITIAL_INVENTORY/DAILY_SALES',
    `data_date` DATE NOT NULL COMMENT '数据归属日期；销售导入时为业务日期',
    `file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '文件 SHA-256 指纹',
    `status` VARCHAR(16) NOT NULL DEFAULT 'VALIDATING'
        COMMENT 'VALIDATING/POSTING/POSTED/REVERSED/FAILED',
    `total_rows` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '原始数据行数',
    `success_rows` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成功行数',
    `error_rows` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '错误行数',
    `operator_name` VARCHAR(100) NULL COMMENT '导入操作人',
    `posted_at` DATETIME(3) NULL COMMENT '正式入账时间',
    `reversed_at` DATETIME(3) NULL COMMENT '撤销时间',
    `reversed_by` VARCHAR(100) NULL COMMENT '撤销操作人',
    `reversed_reason` VARCHAR(500) NULL COMMENT '撤销原因',
    `error_message` TEXT NULL COMMENT '批次错误摘要',
    `imported_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '实际上传时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `active_sales_date` DATE GENERATED ALWAYS AS (
        CASE
            WHEN `import_type` = 'DAILY_SALES' AND `status` = 'POSTED'
                THEN `data_date`
            ELSE NULL
        END
    ) STORED COMMENT '有效销售日期，用于数据库唯一约束',
    `active_initial_inventory` TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN `import_type` = 'INITIAL_INVENTORY' AND `status` = 'POSTED'
                THEN 1
            ELSE NULL
        END
    ) STORED COMMENT '有效初始库存标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_import_batch_file_hash` (`import_type`, `file_hash`),
    UNIQUE KEY `uk_import_batch_active_sales_date` (`active_sales_date`),
    UNIQUE KEY `uk_import_batch_active_initial` (`active_initial_inventory`),
    KEY `idx_import_batch_date_status` (`data_date`, `status`),
    KEY `idx_import_batch_imported_at` (`imported_at`),
    CONSTRAINT `chk_import_batch_type`
        CHECK (`import_type` IN ('INITIAL_INVENTORY', 'DAILY_SALES')),
    CONSTRAINT `chk_import_batch_status`
        CHECK (`status` IN ('VALIDATING', 'POSTING', 'POSTED', 'REVERSED', 'FAILED')),
    CONSTRAINT `chk_import_batch_row_counts`
        CHECK (`success_rows` + `error_rows` <= `total_rows`)
) ENGINE = InnoDB COMMENT = '文件导入批次';

-- -----------------------------------------------------------------------------
-- 8. Excel 原始行：用于审计、错误定位和重新解析
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `import_raw_row` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '原始行主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '导入批次主键',
    `row_number` INT UNSIGNED NOT NULL COMMENT 'Excel 原始行号',
    `barcode` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '解析出的条码',
    `raw_data` JSON NOT NULL COMMENT '原始字段和值',
    `parse_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/VALID/WARNING/INVALID',
    `error_message` VARCHAR(1000) NULL COMMENT '行级错误或警告',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_import_raw_row_batch_row` (`batch_id`, `row_number`),
    KEY `idx_import_raw_row_barcode` (`barcode`),
    CONSTRAINT `fk_import_raw_row_batch`
        FOREIGN KEY (`batch_id`) REFERENCES `import_batch` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_import_raw_row_status`
        CHECK (`parse_status` IN ('PENDING', 'VALID', 'WARNING', 'INVALID'))
) ENGINE = InnoDB COMMENT = 'Excel 原始导入行';

-- -----------------------------------------------------------------------------
-- 9. 商品日销售
--    供应商为空时 supplier_key 取 0，确保同一批次、商品只有一条“未知供应商”记录。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `daily_product_sales` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品日销售主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '销售导入批次主键',
    `business_date` DATE NOT NULL COMMENT '销售业务日期',
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '商品主键',
    `supplier_id` BIGINT UNSIGNED NULL COMMENT '供应商主键，可为空',
    `sales_quantity` DECIMAL(18, 3) NOT NULL DEFAULT 0 COMMENT '净销售数量，可为负',
    `sales_amount` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '净销售收入，可为负',
    `gross_profit_amount` DECIMAL(18, 2) NULL COMMENT '毛利额',
    `reported_gross_profit_rate` DECIMAL(9, 4) NULL
        COMMENT 'POS 原始毛利率百分数，仅供核对',
    `supplier_key` BIGINT UNSIGNED GENERATED ALWAYS AS (
        IFNULL(`supplier_id`, 0)
    ) STORED COMMENT '供应商唯一键辅助列',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_daily_sales_batch_product_supplier`
        (`batch_id`, `product_id`, `supplier_key`),
    KEY `idx_daily_sales_date_product` (`business_date`, `product_id`),
    KEY `idx_daily_sales_product_date` (`product_id`, `business_date`),
    KEY `idx_daily_sales_supplier_date` (`supplier_id`, `business_date`),
    CONSTRAINT `fk_daily_sales_batch`
        FOREIGN KEY (`batch_id`) REFERENCES `import_batch` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_daily_sales_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_daily_sales_supplier`
        FOREIGN KEY (`supplier_id`) REFERENCES `supplier` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE = InnoDB COMMENT = '商品日销售事实';

-- -----------------------------------------------------------------------------
-- 10. 库存流水：所有库存变化均使用有符号 quantity_change
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inventory_movement` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '库存流水主键',
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '商品主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '产生该流水的导入批次',
    `business_date` DATE NOT NULL COMMENT '业务归属日期',
    `movement_type` VARCHAR(32) NOT NULL
        COMMENT 'INITIAL_BALANCE/SALE_OUT/SALE_RETURN/REVERSAL',
    `quantity_change` DECIMAL(18, 3) NOT NULL COMMENT '有符号变化量，增加为正，减少为负',
    `balance_before` DECIMAL(18, 3) NOT NULL COMMENT '变化前库存',
    `balance_after` DECIMAL(18, 3) NOT NULL COMMENT '变化后库存',
    `reversal_of_id` BIGINT UNSIGNED NULL COMMENT '被撤销的原流水',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inventory_movement_reversal` (`reversal_of_id`),
    KEY `idx_inventory_movement_product_date_id` (`product_id`, `business_date`, `id`),
    KEY `idx_inventory_movement_batch_id` (`batch_id`, `id`),
    CONSTRAINT `fk_inventory_movement_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_inventory_movement_batch`
        FOREIGN KEY (`batch_id`) REFERENCES `import_batch` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_inventory_movement_reversal`
        FOREIGN KEY (`reversal_of_id`) REFERENCES `inventory_movement` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_inventory_movement_type`
        CHECK (`movement_type` IN ('INITIAL_BALANCE', 'SALE_OUT', 'SALE_RETURN', 'REVERSAL')),
    CONSTRAINT `chk_inventory_movement_nonzero`
        CHECK (`quantity_change` <> 0),
    CONSTRAINT `chk_inventory_movement_balance`
        CHECK (`balance_after` = `balance_before` + `quantity_change`),
    CONSTRAINT `chk_inventory_movement_reversal_ref`
        CHECK (
            (`movement_type` = 'REVERSAL' AND `reversal_of_id` IS NOT NULL)
            OR (`movement_type` <> 'REVERSAL' AND `reversal_of_id` IS NULL)
        )
) ENGINE = InnoDB COMMENT = '商品库存变化流水';

-- -----------------------------------------------------------------------------
-- 查询视图：只暴露当前有效销售批次
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW `v_posted_daily_product_sales` AS
SELECT
    `s`.`id`,
    `s`.`batch_id`,
    `s`.`business_date`,
    `s`.`product_id`,
    `s`.`supplier_id`,
    `s`.`sales_quantity`,
    `s`.`sales_amount`,
    `s`.`gross_profit_amount`,
    `s`.`reported_gross_profit_rate`,
    `s`.`created_at`
FROM `daily_product_sales` AS `s`
INNER JOIN `import_batch` AS `b`
    ON `b`.`id` = `s`.`batch_id`
WHERE `b`.`import_type` = 'DAILY_SALES'
  AND `b`.`status` = 'POSTED';

-- -----------------------------------------------------------------------------
-- 查询视图：商品、仓库、品类和当前库存
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW `v_product_inventory_query` AS
SELECT
    `p`.`id` AS `product_id`,
    `p`.`barcode`,
    `p`.`product_name`,
    `p`.`unit`,
    `p`.`category_id`,
    `c`.`category_code`,
    `c`.`category_name`,
    `p`.`warehouse_id`,
    `w`.`warehouse_code`,
    `w`.`warehouse_name`,
    `p`.`tax_cost_price`,
    `p`.`sale_price`,
    `p`.`data_status`,
    `i`.`current_quantity`,
    CASE
        WHEN `i`.`current_quantity` < 0 THEN 'NEGATIVE'
        WHEN `i`.`current_quantity` = 0 THEN 'ZERO'
        ELSE 'POSITIVE'
    END AS `inventory_status`,
    `i`.`updated_at` AS `inventory_updated_at`
FROM `product` AS `p`
INNER JOIN `product_inventory` AS `i`
    ON `i`.`product_id` = `p`.`id`
LEFT JOIN `category` AS `c`
    ON `c`.`id` = `p`.`category_id`
LEFT JOIN `warehouse` AS `w`
    ON `w`.`id` = `p`.`warehouse_id`;

-- 建表完成。业务数据初始化和每日销售过账由应用事务执行。
