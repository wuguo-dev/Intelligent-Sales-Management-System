-- =============================================================================
-- 迁移：文件指纹查重改为只约束有效批次，放开撤销/失败后的同文件重传
--
-- 背景：uk_import_batch_file_hash (store_id, import_type, file_hash) 没有状态维度，
-- 撤销批次后拿同一个文件重传会被查重挡住。真实场景是业务日期填错（8-29 的文件填成 8-28）：
-- 文件本身没问题，唯一键又不含 data_date，「改内容让哈希变化」对这个场景不成立。
--
-- 做法与 active_sales_date / active_initial_inventory 完全一致：加生成列表达
-- 「只有 POSTED 批次占用指纹坑位」，唯一键改建在生成列上。REVERSED 与 FAILED 都释放坑位——
-- 失败批次没有产生任何业务数据，修好外部原因（例如先补齐商品资料）后重传属于正常操作。
--
-- 幂等性：本脚本不幂等（ALTER TABLE ADD COLUMN 重复执行会报 1060）。重复执行前先确认
-- active_file_hash 列是否已存在。
-- 影响：加 STORED 生成列会重建表，当前数据量下无影响。
-- =============================================================================

USE `haowugou`;

ALTER TABLE `import_batch`
    ADD COLUMN `active_file_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN `status` = 'POSTED' THEN `file_hash` ELSE NULL END
            ) STORED COMMENT '有效批次的文件指纹；非 POSTED 释放坑位',
    DROP INDEX `uk_import_batch_file_hash`,
    ADD UNIQUE KEY `uk_import_batch_active_file_hash` (`store_id`, `import_type`, `active_file_hash`);
