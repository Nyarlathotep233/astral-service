-- The scene update and its receipt commit together. A lost HTTP response can be
-- recovered after a browser/server restart without incrementing the version twice.
CREATE TABLE IF NOT EXISTS h3d_scene_save_operation (
    scene_id varchar(64) NOT NULL,
    operation_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_version int NOT NULL,
    result_json longtext NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scene_id, operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
