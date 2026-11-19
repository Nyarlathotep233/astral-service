package com.astral.business.scenes.service;

import com.alibaba.fastjson2.JSON;
import com.astral.business.scenes.entity.Astral3DScenes;
import com.astral.business.scenes.entity.SceneSaveRequest;
import com.astral.business.scenes.entity.SceneSaveResult;
import com.astral.business.scenes.mapper.Astral3DScenesMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class SceneSaveService {
    private static final Logger log = LoggerFactory.getLogger(SceneSaveService.class);
    private final Astral3DScenesMapper scenes;
    private final JdbcTemplate jdbc;
    private final SceneSaveAssets assets;
    private final TransactionTemplate transactions;

    public SceneSaveService(Astral3DScenesMapper scenes, JdbcTemplate jdbc,
                            SceneSaveAssets assets, PlatformTransactionManager manager) {
        this.scenes = scenes;
        this.jdbc = jdbc;
        this.assets = assets;
        this.transactions = new TransactionTemplate(manager);
    }

    public SceneSaveResult findOperation(String sceneId, String operationId) {
        validateIdentity(sceneId, operationId);
        List<String> records = jdbc.queryForList(
                "SELECT result_json FROM h3d_scene_save_operation WHERE scene_id=? AND operation_id=?",
                String.class, sceneId, operationId);
        if (records.isEmpty()) return null;
        SceneSaveResult result = JSON.parseObject(records.get(0), SceneSaveResult.class);
        result.setReplayed(true);
        return result;
    }

    public SceneSaveResult save(String sceneId, SceneSaveRequest request) {
        return save(sceneId, request, false);
    }

    public Astral3DScenes saveLegacy(String sceneId, Astral3DScenes scene) {
        // Existing clients send the target version. Keep that PUT contract while
        // giving it the same row lock, CAS and file rollback as the new endpoint.
        SceneSaveRequest request = new SceneSaveRequest();
        request.setScene(scene);
        request.setOperationId("legacy-" + UUID.randomUUID());
        return save(sceneId, request, true).getScene();
    }

    private SceneSaveResult save(String sceneId, SceneSaveRequest request, boolean legacy) {
        if (request == null || request.getScene() == null || (!legacy && (request.getExpectedVersion() == null
                || request.getExpectedVersion() < 0 || request.getExpectedVersion() == Integer.MAX_VALUE))) {
            throw new SceneSaveException(400, "INVALID_SAVE_REQUEST", "保存需要 scene、expectedVersion 和 operationId");
        }
        validateIdentity(sceneId, request.getOperationId());
        final SceneSaveAssets.Backup[] backup = {null};
        final Astral3DScenes[] previous = {null};
        final boolean[] changed = {false};
        try {
            SceneSaveResult result = transactions.execute(status -> {
                // The database row lock serializes all server instances, not just
                // calls in this JVM. The SQL version predicate is still mandatory.
                Astral3DScenes old = scenes.selectOne(new QueryWrapper<Astral3DScenes>().eq("id", sceneId).last("FOR UPDATE"));
                if (old == null) throw new SceneSaveException(404, "SCENE_NOT_FOUND", "场景不存在");
                previous[0] = old;
                int oldVersion = old.getSceneVersion() == null ? 0 : old.getSceneVersion();
                Astral3DScenes next = mergeScene(old, request.getScene());
                int expectedVersion = legacy ? legacyExpectedVersion(request.getScene(), old, next, oldVersion)
                        : request.getExpectedVersion();
                SceneSaveResult replay = findOperation(sceneId, request.getOperationId());
                if (replay != null) {
                    if (!Objects.equals(expectedVersion, replay.getExpectedVersion())) {
                        throw new SceneSaveException(409, "OPERATION_ID_REUSED", "operationId 已用于不同的 expectedVersion");
                    }
                    return replay;
                }
                if (oldVersion == Integer.MAX_VALUE || oldVersion != expectedVersion) {
                    throw new SceneSaveException(409, "VERSION_CONFLICT", "场景已被其他会话保存；expectedVersion="
                            + expectedVersion + ", currentVersion=" + oldVersion + "。请重新加载后保存");
                }
                next.setSceneVersion(oldVersion + 1);
                next.setUpdateTime(new Date());
                try {
                    backup[0] = assets.prepare(old, next, request.getOperationId(), legacy);
                    int updated = scenes.update(next, new UpdateWrapper<Astral3DScenes>()
                            .eq("id", sceneId).apply("COALESCE(sceneVersion, 0) = {0}", expectedVersion));
                    if (updated != 1) throw new SceneSaveException(409, "VERSION_CONFLICT", "场景版本已变化，保存未生效");
                    changed[0] = true;
                    SceneSaveResult receipt = new SceneSaveResult();
                    receipt.setScene(next);
                    receipt.setOperationId(request.getOperationId());
                    receipt.setExpectedVersion(expectedVersion);
                    receipt.setReplayed(false);
                    jdbc.update("INSERT INTO h3d_scene_save_operation (scene_id, operation_id, expected_version, result_json) VALUES (?, ?, ?, ?)",
                            sceneId, request.getOperationId(), expectedVersion, JSON.toJSONString(receipt));
                    jdbc.update("DELETE FROM h3d_legacy_scene_upload WHERE scene_id=? AND target_version<=?",
                            sceneId, next.getSceneVersion());
                    return receipt;
                } catch (SceneSaveException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("场景资源或保存事务失败", e);
                }
            });
            finishAssetCleanup(result, backup[0], sceneId, request.getOperationId());
            return result;
        } catch (Exception error) {
            if (!changed[0]) {
                discardBackup(backup[0], sceneId, request.getOperationId());
                if (error instanceof SceneSaveException) throw (SceneSaveException) error;
                log.error("Scene save failed before update: sceneId={}, operationId={}", sceneId, request.getOperationId(), error);
                throw new SceneSaveException(500, "SAVE_FAILED", "保存失败，场景数据未更新");
            }
            // A connection can fail while commit succeeds. Consult the durable
            // receipt before compensating; never delete this operation's uploads.
            try {
                SceneSaveResult committed = findOperation(sceneId, request.getOperationId());
                if (committed != null) {
                    finishAssetCleanup(committed, backup[0], sceneId, request.getOperationId());
                    return committed;
                }
                // No old resource has been deleted before commit, so a database
                // rollback leaves all referenced files intact even after a crash.
                Astral3DScenes current = scenes.selectById(sceneId);
                if (current == null || !sameVersionAndZip(previous[0], current)) {
                    throw new IllegalStateException("数据库回滚状态无法确认");
                }
                discardBackup(backup[0], sceneId, request.getOperationId());
            } catch (Exception recoveryError) {
                log.error("Scene save rollback failed: sceneId={}, operationId={}, backup={}",
                        sceneId, request.getOperationId(), assets.backupLocation(backup[0]), recoveryError);
                throw new SceneSaveException(500, "ROLLBACK_FAILED", "保存失败且无法确认完整回滚；已保留新文件和备份，请检查服务日志。operationId=" + request.getOperationId());
            }
            log.error("Scene save rolled back: sceneId={}, operationId={}", sceneId, request.getOperationId(), error);
            throw new SceneSaveException(500, "SAVE_FAILED_ROLLED_BACK", "保存失败，数据库与旧资源已回滚。operationId=" + request.getOperationId());
        }
    }

    private void finishAssetCleanup(SceneSaveResult result, SceneSaveAssets.Backup backup, String sceneId, String operationId) {
        if (backup == null) return;
        try {
            // This is intentionally after DB commit. A cleanup error must not
            // undo a published version or delete another tab's successful save.
            assets.retire(backup);
            discardBackup(backup, sceneId, operationId);
        } catch (Exception cleanupError) {
            String warning = "保存已成功；旧资源清理失败，备份已保留";
            try { assets.restore(backup); }
            catch (Exception restoreError) {
                warning = "保存已成功；旧资源清理和恢复失败，备份已保留，请检查服务日志";
                log.error("Retired asset restore failed: sceneId={}, operationId={}, backup={}", sceneId, operationId, assets.backupLocation(backup), restoreError);
            }
            result.setWarnings(java.util.Collections.singletonList(warning));
            log.warn("Retired asset cleanup failed: sceneId={}, operationId={}, backup={}", sceneId, operationId, assets.backupLocation(backup), cleanupError);
            try {
                jdbc.update("UPDATE h3d_scene_save_operation SET result_json=? WHERE scene_id=? AND operation_id=?",
                        JSON.toJSONString(result), sceneId, operationId);
            } catch (Exception receiptError) {
                log.warn("Could not append cleanup warning to receipt: sceneId={}, operationId={}", sceneId, operationId, receiptError);
            }
        }
    }

    private void discardBackup(SceneSaveAssets.Backup backup, String sceneId, String operationId) {
        try { assets.discardBackup(backup); }
        catch (Exception e) { log.warn("Retained scene save backup: sceneId={}, operationId={}, backup={}", sceneId, operationId, assets.backupLocation(backup), e); }
    }

    private boolean sameVersionAndZip(Astral3DScenes a, Astral3DScenes b) {
        return java.util.Objects.equals(a.getSceneVersion(), b.getSceneVersion())
                && java.util.Objects.equals(a.getZip(), b.getZip());
    }

    private int legacyExpectedVersion(Astral3DScenes supplied, Astral3DScenes old, Astral3DScenes next, int oldVersion) {
        Integer target = supplied.getSceneVersion();
        boolean keepsAssets = Objects.equals(old.getZip(), next.getZip())
                && Objects.equals(old.getCoverPicture(), next.getCoverPicture());
        // Merge an unversioned metadata patch under the row lock. When supplied,
        // the target always keeps its CAS meaning, even on a repeated legacy PUT.
        if (keepsAssets && target == null) return oldVersion;
        if (target == null || target <= 0) {
            throw new SceneSaveException(400, "INVALID_SAVE_REQUEST", "替换场景资源需要目标版本号");
        }
        return target - 1;
    }

    private void validateIdentity(String sceneId, String operationId) {
        if (sceneId == null || !sceneId.matches("[A-Za-z0-9_-]{1,64}") || operationId == null
                || !operationId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new SceneSaveException(400, "INVALID_SAVE_ID", "sceneId 或 operationId 格式无效（operationId 支持 1–128 位字母、数字、._:-）");
        }
    }

    private Astral3DScenes mergeScene(Astral3DScenes old, Astral3DScenes supplied) {
        Astral3DScenes next = JSON.parseObject(JSON.toJSONString(old), Astral3DScenes.class);
        // Creation/source ownership and external API metadata are not editor save fields.
        if (supplied.getSceneType() != null) next.setSceneType(supplied.getSceneType());
        if (supplied.getSceneName() != null) next.setSceneName(supplied.getSceneName());
        if (supplied.getSceneIntroduction() != null) next.setSceneIntroduction(supplied.getSceneIntroduction());
        if (supplied.getCoverPicture() != null) next.setCoverPicture(supplied.getCoverPicture());
        if (supplied.getHasDrawing() != null) next.setHasDrawing(supplied.getHasDrawing());
        if (supplied.getZip() != null) next.setZip(supplied.getZip());
        if (supplied.getZipSize() != null) next.setZipSize(supplied.getZipSize());
        if (supplied.getProjectType() != null) next.setProjectType(supplied.getProjectType());
        if (supplied.getCesiumConfig() != null) next.setCesiumConfig(supplied.getCesiumConfig());
        return next;
    }
}
