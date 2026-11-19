package com.astral.common.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Old editors upload all parts to a fixed scene/version directory before their PUT. */
@Service
public class LegacySceneUploads {
    private static final Pattern DIRECTORY = Pattern.compile(
            "^upload/3DEditor/(scene|screenshot)/([A-Za-z0-9_-]{1,64})-V([0-9]+)$");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public LegacySceneUploads(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    /** Return a conflict before storage is touched. An identical retry can finish an interrupted upload. */
    public String reserve(String directory, MultipartFile file) throws IOException {
        Matcher match = DIRECTORY.matcher(directory == null ? "" : directory);
        if (!match.matches()) return null;
        String name = file.getOriginalFilename();
        if (name == null || name.isEmpty() || name.length() > 100 || name.matches(".*[\\\\/:*?\"<>|].*")
                || name.endsWith(".") || name.endsWith(" ")) {
            throw new IllegalArgumentException("场景资源需要有效的文件名");
        }
        String sceneId = match.group(2);
        int version = Integer.parseInt(match.group(3));
        String hash;
        try (InputStream data = file.getInputStream()) {
            hash = DigestUtils.sha256Hex(data);
        }
        // Commit the claim BEFORE storage I/O: a network error during a remote
        // write cannot release the name for a different payload while it is in flight.
        return transactions.execute(status -> {
            List<Integer> versions = jdbc.queryForList("SELECT COALESCE(sceneVersion,0) FROM astral_3d_scenes WHERE id=? FOR UPDATE",
                    Integer.class, sceneId);
            if (versions.isEmpty() || (long) versions.get(0) + 1 != version) {
                return "场景版本已变化，旧版资源未上传，请重新加载项目";
            }
            String part = match.group(1) + "/" + name;
            List<String> hashes = jdbc.queryForList("SELECT sha256 FROM h3d_legacy_scene_upload WHERE scene_id=? AND target_version=? AND part_name=?",
                    String.class, sceneId, version, part);
            if (hashes.isEmpty()) {
                jdbc.update("INSERT INTO h3d_legacy_scene_upload (scene_id,target_version,part_name,sha256) VALUES (?,?,?,?)",
                        sceneId, version, part, hash);
            } else if (!hash.equals(hashes.get(0))) {
                return "同一版本的资源已由另一次保存上传，请使用新版编辑器重新加载后保存";
            }
            return null;
        });
    }
}
