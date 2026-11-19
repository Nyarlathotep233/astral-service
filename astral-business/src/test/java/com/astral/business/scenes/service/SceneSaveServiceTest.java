package com.astral.business.scenes.service;

import com.alibaba.fastjson2.JSON;
import com.astral.business.scenes.entity.Astral3DScenes;
import com.astral.business.scenes.entity.SceneSaveRequest;
import com.astral.business.scenes.entity.SceneSaveResult;
import com.astral.business.scenes.mapper.Astral3DScenesMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SceneSaveServiceTest {
    Astral3DScenesMapper mapper;
    JdbcTemplate jdbc;
    SceneSaveAssets assets;
    PlatformTransactionManager manager;
    SceneSaveService service;
    Astral3DScenes old;
    SceneSaveRequest request;
    SceneSaveAssets.Backup backup;
    String receipt;
    boolean failLedger;

    @BeforeEach void configure() throws Exception {
        mapper = mock(Astral3DScenesMapper.class);
        assets = mock(SceneSaveAssets.class);
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        jdbc = mock(JdbcTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("queryForList")) {
                return receipt == null ? Collections.emptyList() : Collections.singletonList(receipt);
            }
            if (invocation.getMethod().getName().equals("update")) {
                if (failLedger) throw new IllegalStateException("injected ledger write failure");
                return 1;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        old = new Astral3DScenes();
        old.setId("scene1"); old.setSceneVersion(1); old.setZip("old.zip");
        when(mapper.selectOne(any())).thenReturn(old);
        when(mapper.selectById("scene1")).thenReturn(old);
        when(mapper.update(any(), any())).thenReturn(1);
        backup = new SceneSaveAssets.Backup();
        when(assets.prepare(any(), any(), anyString(), anyBoolean())).thenReturn(backup);
        request = new SceneSaveRequest();
        request.setExpectedVersion(1); request.setOperationId("op-1");
        Astral3DScenes updated = new Astral3DScenes(); updated.setZip("new.zip"); request.setScene(updated);
        service = new SceneSaveService(mapper, jdbc, assets, manager);
    }

    @Test void oldFilesAreRetiredOnlyAfterTransactionCommit() throws Exception {
        SceneSaveResult result = service.save("scene1", request);
        assertEquals(2, result.getScene().getSceneVersion());
        InOrder order = inOrder(manager, assets);
        order.verify(manager).commit(any());
        order.verify(assets).retire(backup);
    }

    @Test void staleVersionNeverUpdatesOrTouchesFiles() {
        request.setExpectedVersion(0);
        SceneSaveException error = assertThrows(SceneSaveException.class, () -> service.save("scene1", request));
        assertEquals("VERSION_CONFLICT", error.getReason());
        verify(mapper, never()).update(any(), any());
        verify(manager).rollback(any());
    }

    @Test void sparseLegacyRenameAndExplicitTargetKeepTheStoredZipAndCover() {
        old.setCoverPicture("old/cover.png");
        for (Integer target : new Integer[] {null, 2}) {
            Astral3DScenes patch = new Astral3DScenes();
            patch.setSceneName("new name"); patch.setSceneVersion(target);
            if (target != null) patch.setZip(old.getZip());
            Astral3DScenes updated = service.saveLegacy("scene1", patch);
            assertEquals("new name", updated.getSceneName());
            assertEquals(2, updated.getSceneVersion());
            assertEquals(old.getZip(), updated.getZip());
            assertEquals(old.getCoverPicture(), updated.getCoverPicture());
        }
    }

    @Test void unversionedLegacyAssetReplacementIsRejected() {
        assertThrows(SceneSaveException.class, () -> service.saveLegacy("scene1", request.getScene()));
        verify(mapper, never()).update(any(), any());
    }

    @Test void replayingALegacyTargetCannotPublishTheSameFilesAgain() {
        Astral3DScenes retry = new Astral3DScenes();
        retry.setSceneVersion(old.getSceneVersion());
        retry.setZip(old.getZip());
        assertEquals("VERSION_CONFLICT", assertThrows(SceneSaveException.class,
                () -> service.saveLegacy("scene1", retry)).getReason());
        verify(mapper, never()).update(any(), any());
    }

    @Test void storedReceiptWinsOverNewerSceneVersionWithoutAnotherUpdate() throws Exception {
        SceneSaveResult saved = new SceneSaveResult();
        saved.setScene(old); saved.setOperationId("op-1"); saved.setExpectedVersion(1);
        receipt = JSON.toJSONString(saved);
        old.setSceneVersion(5);
        assertTrue(service.save("scene1", request).isReplayed());
        verify(mapper, never()).update(any(), any());
        verify(assets, never()).prepare(any(), any(), anyString(), anyBoolean());
    }

    @Test void failedLedgerRollsBackDatabaseWithoutRetiringOldFiles() throws Exception {
        failLedger = true;
        SceneSaveException error = assertThrows(SceneSaveException.class, () -> service.save("scene1", request));
        assertEquals("SAVE_FAILED_ROLLED_BACK", error.getReason());
        verify(manager).rollback(any());
        verify(assets, never()).retire(any());
    }

    @Test void unknownRollbackRetainsBackupAndReportsFailureExplicitly() throws Exception {
        failLedger = true;
        when(mapper.selectById("scene1")).thenThrow(new IllegalStateException("database unavailable"));
        SceneSaveException error = assertThrows(SceneSaveException.class, () -> service.save("scene1", request));
        assertEquals("ROLLBACK_FAILED", error.getReason());
        verify(assets, never()).discardBackup(any());
    }

    @Test void cleanupFailureCannotRollBackAnAlreadyCommittedVersion() throws Exception {
        doThrow(new IOException("injected cleanup failure")).when(assets).retire(backup);
        SceneSaveResult result = service.save("scene1", request);
        assertEquals(2, result.getScene().getSceneVersion());
        assertFalse(result.getWarnings().isEmpty());
        verify(manager).commit(any());
        verify(manager, never()).rollback(any());
        verify(assets).restore(backup);
        verify(assets, never()).discardBackup(backup);
    }

    @Test void cleanupAndRestoreFailuresAreVisibleAndKeepTheBackup() throws Exception {
        doThrow(new IOException("cleanup failure")).when(assets).retire(backup);
        doThrow(new IOException("restore failure")).when(assets).restore(backup);
        SceneSaveResult result = service.save("scene1", request);
        assertTrue(result.getWarnings().get(0).contains("恢复失败"));
        verify(assets, never()).discardBackup(backup);
    }
}
