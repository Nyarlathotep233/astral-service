package com.astral.business.scenes.controller;

import com.alibaba.fastjson2.JSONObject;
import com.astral.business.scenes.entity.Astral3DScenesExample;
import com.astral.business.scenes.service.Astral3DScenesExampleService;
import com.astral.common.config.AstralConfig;
import com.astral.common.constant.CommonConstant;
import com.astral.common.result.Result;
import com.astral.common.utils.CommonUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.astral.business.scenes.entity.Astral3DScenes;
import com.astral.business.scenes.service.Astral3DScenesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 场景zip压缩包信息表(Astral3DScenes)表控制层
 */
@RestController
@RequestMapping("/editor3d/scenes")
public class Astral3DScenesController {
    /**
     * 服务对象
     */
    @Autowired
    private Astral3DScenesService astral3DScenesService;

    @Autowired
    private Astral3DScenesExampleService astral3DScenesExampleService;

    /**
     * 新增
     */
    @PostMapping("/add")
    public Result<?> post(@RequestBody Astral3DScenes lb3dEditorScenes) {
        long count = astral3DScenesService.count(new QueryWrapper<Astral3DScenes>().eq("sceneSource", "INTERNAL"));
        if (count > 2000) {
            return Result.error("共享项目场景数量已达上限（2000个），不允许新增");
        }
        lb3dEditorScenes.setSceneSource("INTERNAL");
        lb3dEditorScenes.setExternalSystem(null);
        lb3dEditorScenes.setExternalBizId(null);
        lb3dEditorScenes.setExternalMeta(null);
        if (astral3DScenesService.save(lb3dEditorScenes)) {
            return Result.success(lb3dEditorScenes);
        } else {
            return Result.error("新增失败");
        }
    }

    @GetMapping("/get/{id}")
    public Result<?> getOne(@PathVariable("id") String id) {
        try {
            Astral3DScenes scenes = astral3DScenesService.getById(id);
            if (Objects.isNull(scenes)) {
                return Result.error("场景不存在");
            }
            if ((StringUtils.isEmpty(scenes.getZip()) || StringUtils.isEmpty(scenes.getCoverPicture()))
                    && !StringUtils.isEmpty(scenes.getExampleSceneId())) {
                Astral3DScenesExample scenesExample = astral3DScenesExampleService.getById(scenes.getExampleSceneId());
                if (Objects.nonNull(scenesExample)) {
                    scenes.setZip(scenesExample.getZip());
                    scenes.setCoverPicture(scenesExample.getCoverPicture());
                }
            }
            return Result.success(scenes);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/getAll")
    public Result<?> getAll(HttpServletRequest request) {
        String fieldsStr = request.getParameter("fields");
        List<String> fields = StringUtils.hasLength(fieldsStr) ? Arrays.asList(fieldsStr.split(",")) : new ArrayList<>();
        String limitStr = request.getParameter("limit");
        Integer limit = StringUtils.hasLength(limitStr) ? Integer.parseInt(limitStr) : 10;
        String offsetStr = request.getParameter("offset");
        Integer offset = StringUtils.hasLength(offsetStr) ? Integer.parseInt(offsetStr) : 0;
        String sortbyStr = request.getParameter("sortby");
        List<String> sortby = StringUtils.hasLength(sortbyStr) ? Arrays.asList(sortbyStr.split(",")) : new ArrayList<>();
        String orderStr = request.getParameter("order");
        List<String> order = StringUtils.hasLength(orderStr) ? Arrays.asList(orderStr.split(",")) : new ArrayList<>();
        String queryStr = request.getParameter("query");
        Map<String, String> query = new HashMap<>();
        if (StringUtils.hasLength(queryStr)) {
            try {
                query = Arrays.stream(queryStr.split(",")).map(s -> {
                    String[] split = s.split(":");
                    if (split.length != 2) {
                        throw new IllegalArgumentException("error:无效的查询键/值对");
                    }
                    return split;
                }).collect(Collectors.toMap(s -> s[0], s -> s[1], (v1, v2) -> v1));
            } catch (Exception e) {
                return Result.error(e.getMessage());
            }
        }
        QueryWrapper<Astral3DScenes> queryWrapper = new QueryWrapper<Astral3DScenes>();
        query.forEach((k, v) -> {
            String filedName = StringUtils.replace(k, ".", "__");
            if ("isnull".equals(filedName)) {
                queryWrapper.eq(filedName, "true".equals(v) || "1".equals(v));
            } else {
                queryWrapper.eq(filedName, v);
            }
        });
        if (!CollectionUtils.isEmpty(sortby)) {
            if (sortby.size() == order.size()) {
                for (int i = 0; i < sortby.size(); i++) {
                    if ("desc".equals(order.get(i))) {
                        queryWrapper.orderByDesc(sortby.get(i));
                    } else if ("asc".equals(order.get(i))) {
                        queryWrapper.orderByAsc(sortby.get(i));
                    } else {
                        return Result.error("Error: Invalid order. Must be either [asc|desc]");
                    }
                }

            } else if (order.size() == 1) {
                if ("desc".equals(order.get(0))) {
                    queryWrapper.orderByDesc(sortby);
                } else if ("asc".equals(order.get(0))) {
                    queryWrapper.orderByAsc(sortby);
                }else {
                    return Result.error("Error: Invalid order. Must be either [asc|desc]");
                }
            } else {
                return Result.error("Error: 'sortby', 'order' sizes mismatch or 'order' size is not 1");
            }
        } else {
            if (!CollectionUtils.isEmpty(order)) {
                return Result.error("Error: unused 'order' fields");
            }
        }
        Page<Astral3DScenes> page = new Page<>();
        page.setSize(limit);
        page.setCurrent(offset / limit + 1);
        if (!CollectionUtils.isEmpty(fields)) {
            queryWrapper.select(fields);
        }

        try {
            Page<Astral3DScenes> resultPage = astral3DScenesService.page(page, queryWrapper);

            // 20251104 新增：封面兜底
            List<Astral3DScenes> records = resultPage.getRecords();
            if (!CollectionUtils.isEmpty(records)) {
                // 需要兜底的 exampleSceneId 集合
                Set<String> exampleIdsToFetch = records.stream()
                        .filter(r -> !StringUtils.hasLength(r.getCoverPicture()) && r.getExampleSceneId() != null)
                        .map(Astral3DScenes::getExampleSceneId)
                        .collect(Collectors.toSet());

                if (!CollectionUtils.isEmpty(exampleIdsToFetch)) {
                    // 批量查询示例场景
                    List<Astral3DScenesExample> examples = astral3DScenesExampleService.listByIds(exampleIdsToFetch);
                    Map<String, String> id2Cover = examples.stream()
                            .filter(Objects::nonNull)
                            .filter(e -> StringUtils.hasLength(e.getCoverPicture()))
                            .collect(Collectors.toMap(Astral3DScenesExample::getId, Astral3DScenesExample::getCoverPicture, (a, b) -> a));

                    // 回填 coverPicture
                    for (Astral3DScenes r : records) {
                        if (!StringUtils.hasLength(r.getCoverPicture())) {
                            String exId = r.getExampleSceneId();
                            if (exId != null) {
                                String fallbackCover = id2Cover.get(exId);
                                if (StringUtils.hasLength(fallbackCover)) {
                                    r.setCoverPicture(fallbackCover);
                                }
                            }
                        }
                    }
                }
            }

            long count = astral3DScenesService.count(queryWrapper);
            JSONObject result = new JSONObject();
            result.put("items", resultPage.getRecords());
            result.put("current", offset + 1);
            result.put("pageSize", limit);
            result.put("pages", (count + limit - 1) / limit);
            result.put("total", count);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{id}")
    public Result<?> put(@PathVariable("id") String id, @RequestBody Astral3DScenes lb3dEditorScenes) {
        Astral3DScenes oldScenes = astral3DScenesService.getById(id);
        if (oldScenes == null) {
            return Result.error("场景不存在");
        }

        SceneUpdateRollbackContext context = null;
        try {
            context = createRollbackContext(id, oldScenes, lb3dEditorScenes);

            validateNewAssets(lb3dEditorScenes, context);
            backupOldAssets(context);

            lb3dEditorScenes.setId(id);
            boolean updated = astral3DScenesService.updateById(lb3dEditorScenes);
            if (!updated) {
                throw new RuntimeException("更新失败");
            }
            context.updated = true;

            cleanupOldAssets(context);
            cleanupBackupAssets(context);

            return Result.success(lb3dEditorScenes);
        } catch (Exception e) {
            e.printStackTrace();
            rollbackUpdate(id, oldScenes, context);
            return Result.error("更新失败，已回滚：" + e.getMessage());
        }
    }

    private SceneUpdateRollbackContext createRollbackContext(String id, Astral3DScenes oldScenes, Astral3DScenes newScenes) {
        SceneUpdateRollbackContext context = new SceneUpdateRollbackContext();
        context.sceneId = id;
        context.localUpload = CommonConstant.UPLOAD_TYPE_LOCAL.equalsIgnoreCase(AstralConfig.getUploadType());

        context.oldZip = oldScenes.getZip();
        context.newZip = newScenes.getZip();
        context.oldCoverPicture = oldScenes.getCoverPicture();
        context.newCoverPicture = newScenes.getCoverPicture();

        context.zipChanged = StringUtils.hasLength(context.oldZip)
                && StringUtils.hasLength(context.newZip)
                && !context.oldZip.equals(context.newZip);

        context.coverChanged = StringUtils.hasLength(context.oldCoverPicture)
                && StringUtils.hasLength(context.newCoverPicture)
                && !context.oldCoverPicture.equals(context.newCoverPicture);

        if (context.zipChanged) {
            context.oldZipFolder = extractParentPath(context.oldZip);
            context.newZipFolder = extractParentPath(context.newZip);
        }

        return context;
    }

    private void validateNewAssets(Astral3DScenes newScenes, SceneUpdateRollbackContext context) {
        if (!context.localUpload) {
            return;
        }

        if (StringUtils.hasLength(newScenes.getZip()) && !pathExists(newScenes.getZip())) {
            throw new RuntimeException("新场景zip文件不存在: " + newScenes.getZip());
        }

        if (StringUtils.hasLength(newScenes.getCoverPicture())
                && !isRemotePath(newScenes.getCoverPicture())
                && !pathExists(newScenes.getCoverPicture())) {
            throw new RuntimeException("新场景封面不存在: " + newScenes.getCoverPicture());
        }
    }

    private void backupOldAssets(SceneUpdateRollbackContext context) throws IOException {
        if (!context.localUpload) {
            return;
        }

        if (!context.zipChanged && !context.coverChanged) {
            return;
        }

        Path backupRoot = resolveUploadPath(".scene-save-backup/" + context.sceneId + "/" + System.currentTimeMillis());
        Files.createDirectories(backupRoot);
        context.backupRoot = backupRoot;

        if (context.zipChanged && StringUtils.hasLength(context.oldZipFolder) && pathExists(context.oldZipFolder)) {
            Path sourceZipFolder = resolveUploadPath(context.oldZipFolder);
            Path backupZipFolder = backupRoot.resolve("zip");
            copyPath(sourceZipFolder, backupZipFolder);
            context.backupZipFolder = backupZipFolder;
        }

        if (context.coverChanged && StringUtils.hasLength(context.oldCoverPicture) && pathExists(context.oldCoverPicture)) {
            Path sourceCoverFile = resolveUploadPath(context.oldCoverPicture);
            String extension = getFileExtension(sourceCoverFile.getFileName().toString());
            Path backupCoverFile = backupRoot.resolve("cover" + extension);
            copyPath(sourceCoverFile, backupCoverFile);
            context.backupCoverFile = backupCoverFile;
        }
    }

    private void cleanupOldAssets(SceneUpdateRollbackContext context) {
        // 为了防止误删旧zip后中断，这里先删封面再删zip目录
        if (context.coverChanged && StringUtils.hasLength(context.oldCoverPicture)) {
            if (context.localUpload && !pathExists(context.oldCoverPicture)) {
                // 历史坏数据（数据库指向的旧文件已不存在）直接跳过删除
            } else if (!CommonUtils.deleteFile(context.oldCoverPicture)) {
                throw new RuntimeException(context.oldCoverPicture + " 删除文件失败");
            }
        }

        if (context.zipChanged && StringUtils.hasLength(context.oldZipFolder)) {
            if (context.localUpload && !pathExists(context.oldZipFolder)) {
                // 历史坏数据（数据库指向的旧文件已不存在）直接跳过删除
            } else if (!CommonUtils.deleteFile(context.oldZipFolder)) {
                throw new RuntimeException(context.oldZipFolder + " 删除文件失败");
            }
        }
    }

    private void rollbackUpdate(String id, Astral3DScenes oldScenes, SceneUpdateRollbackContext context) {
        if (context == null) {
            return;
        }

        if (context.updated) {
            try {
                oldScenes.setId(id);
                astral3DScenesService.updateById(oldScenes);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 删除本次新上传文件，避免失败后堆积垃圾文件
        if (context.coverChanged && StringUtils.hasLength(context.newCoverPicture)) {
            safeDelete(context.newCoverPicture);
        }
        if (context.zipChanged && StringUtils.hasLength(context.newZipFolder)) {
            safeDelete(context.newZipFolder);
        }

        restoreOldAssets(context);
        cleanupBackupAssets(context);
    }

    private void restoreOldAssets(SceneUpdateRollbackContext context) {
        if (!context.localUpload) {
            return;
        }

        try {
            if (context.backupCoverFile != null && StringUtils.hasLength(context.oldCoverPicture)) {
                Path targetCover = resolveUploadPath(context.oldCoverPicture);
                copyPath(context.backupCoverFile, targetCover);
            }
            if (context.backupZipFolder != null && StringUtils.hasLength(context.oldZipFolder)) {
                Path targetZipFolder = resolveUploadPath(context.oldZipFolder);
                copyPath(context.backupZipFolder, targetZipFolder);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void cleanupBackupAssets(SceneUpdateRollbackContext context) {
        if (context == null || context.backupRoot == null) {
            return;
        }

        try {
            File backupRootFile = context.backupRoot.toFile();
            if (backupRootFile.exists()) {
                org.apache.commons.io.FileUtils.deleteDirectory(backupRootFile);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void safeDelete(String relativePath) {
        try {
            CommonUtils.deleteFile(relativePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String extractParentPath(String path) {
        if (!StringUtils.hasLength(path)) {
            return path;
        }
        int lastIndex = path.lastIndexOf("/");
        return lastIndex > 0 ? path.substring(0, lastIndex) : path;
    }

    private boolean isRemotePath(String path) {
        return path.startsWith("http://") || path.startsWith("https://");
    }

    private boolean pathExists(String relativePath) {
        if (!StringUtils.hasLength(relativePath)) {
            return false;
        }
        Path path = resolveUploadPath(relativePath);
        return Files.exists(path);
    }

    private Path resolveUploadPath(String relativePath) {
        String fixedPath = relativePath;
        while (fixedPath.startsWith("/")) {
            fixedPath = fixedPath.substring(1);
        }
        return Paths.get(AstralConfig.getUploadDir(), fixedPath).normalize();
    }

    private void copyPath(Path source, Path target) throws IOException {
        File sourceFile = source.toFile();
        File targetFile = target.toFile();

        if (!sourceFile.exists()) {
            return;
        }

        if (sourceFile.isDirectory()) {
            org.apache.commons.io.FileUtils.copyDirectory(sourceFile, targetFile);
            return;
        }

        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        org.apache.commons.io.FileUtils.copyFile(sourceFile, targetFile);
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf(".");
        if (dot >= 0) {
            return fileName.substring(dot);
        }
        return "";
    }

    private static class SceneUpdateRollbackContext {
        private String sceneId;
        private boolean localUpload;
        private boolean zipChanged;
        private boolean coverChanged;
        private boolean updated;

        private String oldZip;
        private String newZip;
        private String oldZipFolder;
        private String newZipFolder;
        private String oldCoverPicture;
        private String newCoverPicture;

        private Path backupRoot;
        private Path backupZipFolder;
        private Path backupCoverFile;
    }

    @DeleteMapping("/del/{id}")
    public Result<?> delete(@PathVariable String id) {
        // 当前 removeById 对 astral_3d_scenes 是物理删除。
        // 如果以后改成逻辑删除，需要同步检查 H3DOpenApiScenesController.findExternalScene 的幂等查询，
        // 避免外部接口用相同 externalSystem + externalBizId 重新创建时命中已删除记录。
        return Result.toAjax(astral3DScenesService.removeById(id));
    }
}

