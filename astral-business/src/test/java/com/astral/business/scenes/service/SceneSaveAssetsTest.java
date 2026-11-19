package com.astral.business.scenes.service;

import com.astral.business.scenes.entity.Astral3DScenes;
import com.astral.common.config.AstralConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SceneSaveAssetsTest {
    @TempDir Path root;
    SceneSaveAssets assets = new SceneSaveAssets();

    @BeforeEach void configure() {
        AstralConfig config = new AstralConfig();
        config.setUploadType("local");
        config.setUploadDir(root.toString());
    }

    @Test void backupRestoresRetiredFilesWithoutTouchingNewUploads() throws Exception {
        Astral3DScenes old = scene(1, "old", "before");
        Astral3DScenes next = scene(2, "op-attempt", "after");
        SceneSaveAssets.Backup backup = assets.prepare(old, next, "op", false);
        assertTrue(Files.exists(root.resolve(old.getZip())), "Preparing a save must not remove old assets");
        assets.retire(backup);
        assertFalse(Files.exists(root.resolve(old.getZip())));
        assertEquals("after", read(next.getZip()));
        assets.restore(backup);
        assertEquals("before", read(old.getZip()));
        assertEquals("before", read(old.getCoverPicture()));
        assertEquals("after", read(next.getZip()));
        assertTrue(Files.exists(backup.root));
        assets.discardBackup(backup);
        assertFalse(Files.exists(backup.root));
    }

    @Test void overlappingDirectoriesAreRejectedBeforeAnyDeletion() throws Exception {
        Astral3DScenes old = scene(1, "old", "before");
        Astral3DScenes next = scene(2, "op-attempt", "after");
        next.setZip(old.getZip().replace("scene.zip", "other.zip"));
        write(next.getZip(), "other");
        assertThrows(SceneSaveException.class, () -> assets.prepare(old, next, "op", true));
        assertEquals("before", read(old.getZip()));
    }

    @Test void metadataOnlyLegacyPutPreservesBothRealFilesAndAnEmptyProject() throws Exception {
        Astral3DScenes existing = scene(1, "old", "before");
        SceneSaveAssets.Backup backup = assets.prepare(existing, existing, "rename", true);
        assets.retire(backup);
        assertNull(backup.root);
        assertEquals("before", read(existing.getZip()));
        assertEquals("before", read(existing.getCoverPicture()));
        Astral3DScenes empty = new Astral3DScenes();
        assertNull(assets.prepare(empty, empty, "rename", true).root);
    }

    @Test void templateAssetsAreNeverRetired() throws Exception {
        Astral3DScenes old = new Astral3DScenes();
        old.setZip("examples/shared/scene.zip");
        write(old.getZip(), "template");
        Astral3DScenes next = scene(2, "op-attempt", "after");
        SceneSaveAssets.Backup backup = assets.prepare(old, next, "op", false);
        assets.retire(backup);
        assertEquals("template", read(old.getZip()));
    }

    @Test void legacyFlatZipCannotRetireSharedSceneDirectory() throws Exception {
        Astral3DScenes old = new Astral3DScenes();
        old.setZip("upload/3DEditor/scene/scene1-V1.zip");
        write(old.getZip(), "original");
        String sibling = "upload/3DEditor/scene/other-V1/scene.zip";
        write(sibling, "other project");
        Astral3DScenes next = scene(2, "op-attempt", "after");
        SceneSaveAssets.Backup backup = assets.prepare(old, next, "op", false);
        assets.retire(backup);
        assertEquals("other project", read(sibling));
        assertEquals("after", read(next.getZip()));
    }

    @Test void foreignOrTraversingUploadPathsAreRejected() throws Exception {
        Astral3DScenes old = new Astral3DScenes();
        Astral3DScenes next = scene(2, "op-attempt", "after");
        assertThrows(SceneSaveException.class, () -> assets.prepare(old, next, "other-op", false));
        next.setZip("../outside.zip");
        assertThrows(SceneSaveException.class, () -> assets.prepare(old, next, "op", false));
    }

    private Astral3DScenes scene(int version, String suffix, String content) throws Exception {
        Astral3DScenes scene = new Astral3DScenes();
        scene.setId("scene1");
        scene.setSceneVersion(version);
        scene.setZip("upload/3DEditor/scene/scene1-V" + version + "-" + suffix + "/scene.zip");
        scene.setCoverPicture("upload/3DEditor/screenshot/scene1-V" + version + "-" + suffix + "/cover.png");
        write(scene.getZip(), content);
        write(scene.getCoverPicture(), content);
        return scene;
    }
    private void write(String path, String text) throws Exception {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, text.getBytes(StandardCharsets.UTF_8));
    }
    private String read(String path) throws Exception { return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8); }
}
