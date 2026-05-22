SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes_example` ADD COLUMN `templateKey` varchar(64) NULL DEFAULT NULL COMMENT ''模板稳定业务Key'' AFTER `id`',
        'DO 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes_example'
      AND COLUMN_NAME = 'templateKey'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes_example` ADD COLUMN `templateMeta` text NULL COMMENT ''模板扩展元数据JSON'' AFTER `templateKey`',
        'DO 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes_example'
      AND COLUMN_NAME = 'templateMeta'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `astral_3d_scenes_example` ADD UNIQUE KEY `uk_scene_example_template_key` (`templateKey`)',
        'DO 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'astral_3d_scenes_example'
      AND INDEX_NAME = 'uk_scene_example_template_key'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
