-- Operation and attempt IDs isolate competing uploads. The previous 128-character
-- zip column was too short for these paths and normal scene filenames.
ALTER TABLE astral_3d_scenes MODIFY COLUMN zip varchar(1024)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '场景zip包';
