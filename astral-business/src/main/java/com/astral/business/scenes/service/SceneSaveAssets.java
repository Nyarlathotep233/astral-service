package com.astral.business.scenes.service;

import com.astral.business.scenes.entity.Astral3DScenes;
import com.astral.common.config.AstralConfig;
import com.astral.common.constant.CommonConstant;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.Objects;

/** File backup/restore extracted from the legacy scene controller.
 * Only a scene's own retired upload directories may be removed; template assets
 * and remote objects remain intact. New uploads stay intact on uncertain commits.
 */
@Component
public class SceneSaveAssets {
    public static class Backup {
        Path root;
        Path oldZipFolder;
        Path oldCover;
        Path zipBackup;
        Path coverBackup;
    }

    public Backup prepare(Astral3DScenes previous, Astral3DScenes next,
                          String operationId, boolean legacy) throws IOException {
        Backup backup = new Backup();
        if (!CommonConstant.UPLOAD_TYPE_LOCAL.equalsIgnoreCase(AstralConfig.getUploadType())) {
            // Remote stores have no restore API. Retain previous immutable objects.
            return backup;
        }
        String zipPrefix = "upload/3DEditor/scene/" + next.getId() + "-V";
        String coverPrefix = "upload/3DEditor/screenshot/" + next.getId() + "-V";
        boolean replaceZip = !legacy || !Objects.equals(previous.getZip(), next.getZip());
        boolean replaceCover = !legacy || !Objects.equals(previous.getCoverPicture(), next.getCoverPicture());
        if (replaceZip && !StringUtils.hasText(next.getZip())) {
            throw new SceneSaveException(400, "INVALID_ASSET", "保存缺少场景压缩包");
        }
        Path newZip = replaceZip ? resolve(next.getZip()) : null;
        if (replaceZip && !Files.isRegularFile(newZip)) {
            throw new SceneSaveException(400, "INVALID_ASSET", "新场景压缩包不存在");
        }
        if (!legacy) {
            String suffix = next.getSceneVersion() + "-" + operationId.replaceAll("[^A-Za-z0-9_-]", "_") + "-";
            if (!next.getZip().startsWith(zipPrefix + suffix)) {
                throw new SceneSaveException(400, "INVALID_ASSET", "压缩包必须位于本次 operationId 的独立上传目录");
            }
            if (StringUtils.hasText(next.getCoverPicture()) && !isRemote(next.getCoverPicture())
                    && !next.getCoverPicture().startsWith(coverPrefix + suffix)) {
                throw new SceneSaveException(400, "INVALID_ASSET", "封面必须位于本次 operationId 的独立上传目录");
            }
        }
        if (replaceCover && StringUtils.hasText(next.getCoverPicture()) && !isRemote(next.getCoverPicture())
                && !Files.isRegularFile(resolve(next.getCoverPicture()))) {
            throw new SceneSaveException(400, "INVALID_ASSET", "新场景封面不存在");
        }
        // The old implementation deleted the entire old package directory. Reject
        // overlapping directories so that cleanup cannot delete the newly saved zip.
        if (replaceZip && StringUtils.hasText(previous.getZip()) && !isRemote(previous.getZip())) {
            Path oldFolder = resolve(previous.getZip()).getParent();
            Path newFolder = newZip.getParent();
            if (previous.getZip().startsWith(zipPrefix) && resolve("upload/3DEditor/scene").equals(oldFolder.getParent())) {
                if (oldFolder.startsWith(newFolder) || newFolder.startsWith(oldFolder)) {
                    throw new SceneSaveException(400, "OVERLAPPING_ASSETS", "新旧场景压缩包目录必须独立");
                }
                if (Files.exists(oldFolder)) backup.oldZipFolder = oldFolder;
            }
        }
        if (StringUtils.hasText(previous.getCoverPicture()) && previous.getCoverPicture().startsWith(coverPrefix)
                && !previous.getCoverPicture().equals(next.getCoverPicture())) {
            Path oldCover = resolve(previous.getCoverPicture());
            if (Files.exists(oldCover)) backup.oldCover = oldCover;
        }
        if (backup.oldZipFolder != null || backup.oldCover != null) {
            backup.root = resolve(".scene-save-backup/" + next.getId() + "/" + UUID.randomUUID());
            Files.createDirectories(backup.root);
            if (backup.oldZipFolder != null) {
                backup.zipBackup = backup.root.resolve("zip");
                FileUtils.copyDirectory(backup.oldZipFolder.toFile(), backup.zipBackup.toFile());
            }
            if (backup.oldCover != null) {
                backup.coverBackup = backup.root.resolve("cover");
                FileUtils.copyFile(backup.oldCover.toFile(), backup.coverBackup.toFile());
            }
        }
        return backup;
    }

    public void retire(Backup backup) throws IOException {
        if (backup.oldCover != null) FileUtils.forceDelete(backup.oldCover.toFile());
        if (backup.oldZipFolder != null) FileUtils.deleteDirectory(backup.oldZipFolder.toFile());
    }

    public void restore(Backup backup) throws IOException {
        if (backup == null) return;
        // Restore errors must propagate. Never discard the only backup on failure.
        if (backup.coverBackup != null) FileUtils.copyFile(backup.coverBackup.toFile(), backup.oldCover.toFile());
        if (backup.zipBackup != null) FileUtils.copyDirectory(backup.zipBackup.toFile(), backup.oldZipFolder.toFile());
    }

    public void discardBackup(Backup backup) throws IOException {
        if (backup != null && backup.root != null) FileUtils.deleteDirectory(backup.root.toFile());
    }

    public String backupLocation(Backup backup) {
        return backup == null || backup.root == null ? "none" : backup.root.toString();
    }

    private boolean isRemote(String value) { return value.startsWith("https://") || value.startsWith("http://"); }

    private Path resolve(String relative) {
        Path root = Paths.get(AstralConfig.getUploadDir()).toAbsolutePath().normalize();
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || result.equals(root) || relative.contains("\\") || relative.contains("..")) {
            throw new SceneSaveException(400, "INVALID_ASSET_PATH", "场景资源路径不在上传目录内");
        }
        return result;
    }
}
