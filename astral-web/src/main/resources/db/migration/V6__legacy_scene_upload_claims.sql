-- Legacy clients have no per-tab upload ID. Bind every part name to its bytes
-- until the target version is committed, including across server restarts.
CREATE TABLE h3d_legacy_scene_upload (
    scene_id varchar(64) NOT NULL,
    target_version int NOT NULL,
    part_name varchar(128) NOT NULL,
    sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (scene_id, target_version, part_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
