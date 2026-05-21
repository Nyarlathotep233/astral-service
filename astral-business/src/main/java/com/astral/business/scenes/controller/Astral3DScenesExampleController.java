package com.astral.business.scenes.controller;

import com.alibaba.fastjson2.JSONObject;
import com.astral.business.scenes.entity.Astral3DScenes;
import com.astral.business.scenes.entity.Astral3DScenesExample;
import com.astral.business.scenes.service.Astral3DScenesExampleService;
import com.astral.business.scenes.service.Astral3DScenesService;
import com.astral.common.config.AstralConfig;
import com.astral.common.constant.CommonConstant;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import org.apache.commons.io.FileUtils;
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
 * 新建场景时的示例表(Astral3DScenesExample)表控制层
 */
@RestController
@RequestMapping("/editor3d/sceneExample")
public class Astral3DScenesExampleController {

    private static final String ADMIN_KEY_HEADER = "X-H3D-Admin-Key";
    private static final String TEMPLATE_PACKAGE_DIR = "example/scenes/packages";
    private static final String TEMPLATE_SCREENSHOT_DIR = "example/scenes/screenshot";

    /**
     * 服务对象
     */
    @Autowired
    private Astral3DScenesExampleService astral3DScenesExampleService;

    @Autowired
    private Astral3DScenesService astral3DScenesService;

    /**
     * 新增
     * @return
     */
    @PostMapping
    public Result<?> post(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
                          @RequestBody Astral3DScenesExample lb3dEditorScenesExample) {
        Result<?> authResult = validateAdminKey(adminKey);
        if (authResult != null) {
            return authResult;
        }

        applyTemplateDefaults(lb3dEditorScenesExample);
        if (astral3DScenesExampleService.save(lb3dEditorScenesExample)) {
            return Result.success(lb3dEditorScenesExample);
        } else {
            return Result.error("新增失败");
        }

    }

    @PostMapping("/fromScene/{sceneId}")
    public Result<?> createFromScene(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
                                     @PathVariable("sceneId") String sceneId,
                                     @RequestBody(required = false) TemplateFromSceneRequest request) {
        Result<?> authResult = validateAdminKey(adminKey);
        if (authResult != null) {
            return authResult;
        }
        if (!CommonConstant.UPLOAD_TYPE_LOCAL.equalsIgnoreCase(AstralConfig.getUploadType())) {
            return Result.error("当前仅支持本地上传模式下从场景生成模板");
        }

        Astral3DScenes scene = astral3DScenesService.getById(sceneId);
        if (scene == null || Integer.valueOf(1).equals(scene.getDelTag())) {
            return Result.error("场景不存在");
        }
        if (!StringUtils.hasText(scene.getZip()) || !StringUtils.hasText(scene.getCoverPicture())) {
            return Result.error("源场景未保存完整，缺少zip或封面");
        }
        if (isRemotePath(scene.getCoverPicture())) {
            return Result.error("源场景封面不是本地资源，暂不支持生成模板");
        }

        Path sourceZipFile = resolveUploadPath(scene.getZip());
        Path sourceZipFolder = sourceZipFile.getParent();
        Path sourceCoverFile = resolveUploadPath(scene.getCoverPicture());
        if (sourceZipFolder == null || !Files.isDirectory(sourceZipFolder) || !Files.isRegularFile(sourceZipFile)) {
            return Result.error("源场景zip文件不存在");
        }
        if (!Files.isRegularFile(sourceCoverFile)) {
            return Result.error("源场景封面不存在");
        }

        String templateId = UUID.randomUUID().toString();
        String coverExtension = getFileExtension(sourceCoverFile.getFileName().toString());
        if (!StringUtils.hasText(coverExtension)) {
            coverExtension = ".png";
        }

        String templatePackageDir = TEMPLATE_PACKAGE_DIR + "/" + templateId;
        String templateZip = templatePackageDir + "/" + sourceZipFile.getFileName().toString();
        String templateCover = TEMPLATE_SCREENSHOT_DIR + "/" + templateId + coverExtension;
        Path targetZipFolder = resolveUploadPath(templatePackageDir);
        Path targetCoverFile = resolveUploadPath(templateCover);

        boolean copied = false;
        try {
            copyPath(sourceZipFolder, targetZipFolder);
            copyPath(sourceCoverFile, targetCoverFile);
            copied = true;

            Astral3DScenesExample template = new Astral3DScenesExample();
            template.setId(templateId);
            template.setSceneName(resolveString(request == null ? null : request.getSceneName(), scene.getSceneName(), "未命名模板"));
            template.setSceneType(resolveString(request == null ? null : request.getSceneType(), scene.getSceneType(), "其他"));
            template.setSceneVersion(scene.getSceneVersion() == null ? 1 : scene.getSceneVersion());
            template.setSceneIntroduction(resolveNullableString(request == null ? null : request.getSceneIntroduction(), scene.getSceneIntroduction()));
            template.setCoverPicture(templateCover);
            template.setHasDrawing(scene.getHasDrawing() == null ? 0 : scene.getHasDrawing());
            template.setZip(templateZip);
            template.setZipSize(resolveString(null, scene.getZipSize(), "0KB"));
            template.setProjectType(scene.getProjectType() == null ? 0 : scene.getProjectType());
            template.setCesiumConfig(scene.getCesiumConfig());
            template.setDelTag(0);

            if (!astral3DScenesExampleService.save(template)) {
                throw new RuntimeException("保存模板失败");
            }
            return Result.success(template);
        } catch (Exception e) {
            if (copied) {
                safeDeletePath(targetZipFolder);
                safeDeletePath(targetCoverFile);
            }
            return Result.error("从场景生成模板失败：" + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public Result<?> getOne(@PathVariable("id") String id) {
        try {
            Astral3DScenesExample example = astral3DScenesExampleService.getById(id);
            if (example != null && Integer.valueOf(1).equals(example.getDelTag())) {
                return Result.success((Object) null);
            }
            return Result.success(example);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping
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
        QueryWrapper<Astral3DScenesExample> queryWrapper = new QueryWrapper<Astral3DScenesExample>();
        queryWrapper.eq("delTag", 0);
        query.forEach((k, v) -> {
            String filedName = StringUtils.replace(k, ".", "__");
            if ("delTag".equals(filedName)) {
                return;
            }
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
        Page<Astral3DScenesExample> page = new Page<>();
        page.setSize(limit);
        page.setCurrent(offset / limit + 1);
        if (!CollectionUtils.isEmpty(fields)) {
            queryWrapper.select(fields);
        }
        try {
            Page<Astral3DScenesExample> resultPage = astral3DScenesExampleService.page(page, queryWrapper);
            long count = astral3DScenesExampleService.count(queryWrapper);
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

    @PutMapping("/{id}")
    public Result<?> put(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
                         @PathVariable("id") String id,
                         @RequestBody Astral3DScenesExample lb3dEditorScenesExample) {
        Result<?> authResult = validateAdminKey(adminKey);
        if (authResult != null) {
            return authResult;
        }

        lb3dEditorScenesExample.setId(id);
        return Result.toAjax(astral3DScenesExampleService.updateById(lb3dEditorScenesExample));
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
                            @PathVariable("id") String id) {
        Result<?> authResult = validateAdminKey(adminKey);
        if (authResult != null) {
            return authResult;
        }

        Astral3DScenesExample example = astral3DScenesExampleService.getById(id);
        if (example == null) {
            return Result.error("模板不存在");
        }
        example.setDelTag(1);
        example.setDelTime(new Date());
        return Result.toAjax(astral3DScenesExampleService.updateById(example));
    }

    private Result<?> validateAdminKey(String adminKey) {
        String expected = AstralConfig.getAdminApiKey();
        if (!StringUtils.hasText(expected) || !expected.equals(adminKey)) {
            return Result.error401("Invalid admin key");
        }
        return null;
    }

    private void applyTemplateDefaults(Astral3DScenesExample template) {
        if (template.getSceneVersion() == null) {
            template.setSceneVersion(1);
        }
        if (template.getHasDrawing() == null) {
            template.setHasDrawing(0);
        }
        if (template.getProjectType() == null) {
            template.setProjectType(0);
        }
        if (!StringUtils.hasText(template.getZipSize())) {
            template.setZipSize("0KB");
        }
        if (template.getDelTag() == null) {
            template.setDelTag(0);
        }
    }

    private String resolveString(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second;
        }
        return fallback;
    }

    private String resolveNullableString(String first, String second) {
        if (first != null) {
            return first;
        }
        return second;
    }

    private boolean isRemotePath(String path) {
        return path.startsWith("http://") || path.startsWith("https://");
    }

    private Path resolveUploadPath(String relativePath) {
        String fixedPath = relativePath;
        while (fixedPath.startsWith("/")) {
            fixedPath = fixedPath.substring(1);
        }
        Path uploadRoot = Paths.get(AstralConfig.getUploadDir()).toAbsolutePath().normalize();
        Path resolved = uploadRoot.resolve(fixedPath).normalize();
        if (!resolved.startsWith(uploadRoot)) {
            throw new RuntimeException("非法资源路径: " + relativePath);
        }
        return resolved;
    }

    private void copyPath(Path source, Path target) throws IOException {
        File sourceFile = source.toFile();
        File targetFile = target.toFile();

        if (!sourceFile.exists()) {
            throw new IOException("文件不存在: " + source);
        }

        if (sourceFile.isDirectory()) {
            FileUtils.copyDirectory(sourceFile, targetFile);
            return;
        }

        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileUtils.copyFile(sourceFile, targetFile);
    }

    private void safeDeletePath(Path path) {
        try {
            File file = path.toFile();
            if (!file.exists()) {
                return;
            }
            if (file.isDirectory()) {
                FileUtils.deleteDirectory(file);
            } else {
                FileUtils.forceDelete(file);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf(".");
        if (dot >= 0) {
            return fileName.substring(dot);
        }
        return "";
    }

    @Data
    public static class TemplateFromSceneRequest {
        private String sceneName;
        private String sceneType;
        private String sceneIntroduction;
    }
}


