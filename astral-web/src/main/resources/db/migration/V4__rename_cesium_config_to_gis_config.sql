ALTER TABLE `astral_3d_scenes`
    CHANGE COLUMN `cesiumConfig` `gisConfig` varchar(1000)
    CHARACTER SET utf8mb3 COLLATE utf8mb3_unicode_ci NULL DEFAULT NULL
    COMMENT 'WebGIS项目配置';

ALTER TABLE `astral_3d_scenes_example`
    CHANGE COLUMN `cesiumConfig` `gisConfig` varchar(1000)
    CHARACTER SET utf8mb3 COLLATE utf8mb3_unicode_ci NULL DEFAULT NULL
    COMMENT 'WebGIS项目配置';
