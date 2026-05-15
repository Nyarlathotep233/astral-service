SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes` ADD COLUMN `sceneSource` varchar(32) NOT NULL DEFAULT ''INTERNAL'' COMMENT ''场景来源：INTERNAL-前端内部创建，EXTERNAL-外部系统创建'' AFTER `cesiumConfig`',
        'DO 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes'
      AND COLUMN_NAME = 'sceneSource'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes` ADD COLUMN `externalSystem` varchar(64) NULL DEFAULT NULL COMMENT ''外部系统标识'' AFTER `sceneSource`',
        'DO 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes'
      AND COLUMN_NAME = 'externalSystem'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes` ADD COLUMN `externalBizId` varchar(128) NULL DEFAULT NULL COMMENT ''外部系统业务ID'' AFTER `externalSystem`',
        'DO 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes'
      AND COLUMN_NAME = 'externalBizId'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes` ADD COLUMN `externalMeta` text NULL COMMENT ''外部系统扩展元数据JSON'' AFTER `externalBizId`',
        'DO 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes'
      AND COLUMN_NAME = 'externalMeta'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes` ADD INDEX `idx_scene_source_update_time` (`sceneSource`, `updateTime`)',
        'DO 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes'
      AND INDEX_NAME = 'idx_scene_source_update_time'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes` ADD UNIQUE KEY `uk_external_system_biz_id` (`externalSystem`, `externalBizId`)',
        'DO 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes'
      AND INDEX_NAME = 'uk_external_system_biz_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
