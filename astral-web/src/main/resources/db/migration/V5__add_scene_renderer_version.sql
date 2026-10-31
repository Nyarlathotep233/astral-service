ALTER TABLE `astral_3d_scenes`
    ADD COLUMN `rendererVersion` varchar(16) NULL DEFAULT NULL
    COMMENT '创建或最后保存的前端版本：webgl / webgpu；NULL 为未标记'
    AFTER `sceneVersion`;
