package com.astral.business.scenes.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.astral.business.scenes.entity.Astral3DScenes;
import com.astral.business.scenes.entity.Astral3DScenesExample;
import com.astral.business.scenes.service.Astral3DScenesExampleService;
import com.astral.business.scenes.service.Astral3DScenesService;
import com.astral.common.config.AstralConfig;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 外部系统场景 OpenAPI。
 *
 * 实际访问路径会叠加 server.servlet.context-path=/api：
 * /api/openapi/v1/scenes
 */
@RestController
@RequestMapping("/openapi/v1/scenes")
public class H3DOpenApiScenesController {

    private static final String API_KEY_HEADER = "X-H3D-Api-Key";
    private static final String SCENE_SOURCE_EXTERNAL = "EXTERNAL";
    private static final String DEFAULT_SCENE_TYPE = "其他";

    @Autowired
    private Astral3DScenesService astral3DScenesService;

    @Autowired
    private Astral3DScenesExampleService astral3DScenesExampleService;

    @GetMapping("/templates")
    public Result<?> templates(@RequestHeader(value = API_KEY_HEADER, required = false) String apiKey) {
        Result<?> authResult = validateApiKey(apiKey);
        if (authResult != null) {
            return authResult;
        }

        List<Astral3DScenesExample> examples = astral3DScenesExampleService.list(
                new QueryWrapper<Astral3DScenesExample>()
                        .eq("delTag", 0)
                        .orderByDesc("updateTime")
        );

        List<JSONObject> result = examples.stream()
                .filter(Objects::nonNull)
                .map(this::toTemplateResponse)
                .collect(Collectors.toList());

        return Result.success(result);
    }

    @PostMapping({"", "/"})
    public Result<?> createScene(@RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
                                 @RequestBody ExternalSceneCreateRequest request) {
        Result<?> authResult = validateApiKey(apiKey);
        if (authResult != null) {
            return authResult;
        }

        String validationError = validateCreateRequest(request);
        if (validationError != null) {
            return Result.error(validationError);
        }

        String externalSystem = trim(request.getExternalSystem());
        String externalBizId = trim(request.getExternalBizId());
        Astral3DScenes existing = findExternalScene(externalSystem, externalBizId);
        if (existing != null) {
            return Result.success(toCreateResponse(existing, false));
        }

        long count = astral3DScenesService.count();
        if (count > 2000) {
            return Result.error("场景数量已达上限（2000个），不允许新增");
        }

        Astral3DScenesExample template = null;
        String templateId = trim(request.getTemplateId());
        if (StringUtils.hasText(templateId)) {
            template = astral3DScenesExampleService.getById(templateId);
            if (template == null || Integer.valueOf(1).equals(template.getDelTag())) {
                return Result.error("模板不存在");
            }
        }

        Astral3DScenes scene = buildExternalScene(request, template, externalSystem, externalBizId);
        try {
            boolean saved = astral3DScenesService.save(scene);
            if (!saved) {
                return Result.error("创建场景失败");
            }
            return Result.success(toCreateResponse(scene, true));
        } catch (DuplicateKeyException e) {
            // 并发重试场景下，唯一键可能先被另一请求写入；按幂等语义返回已有场景。
            Astral3DScenes duplicated = findExternalScene(externalSystem, externalBizId);
            if (duplicated != null) {
                return Result.success(toCreateResponse(duplicated, false));
            }
            throw e;
        }
    }

    private Result<?> validateApiKey(String apiKey) {
        String expected = AstralConfig.getExternalApiKey();
        if (!StringUtils.hasText(expected) || !expected.equals(apiKey)) {
            return Result.error401("Invalid API key");
        }
        return null;
    }

    private String validateCreateRequest(ExternalSceneCreateRequest request) {
        if (request == null) {
            return "请求体不能为空";
        }
        if (!StringUtils.hasText(request.getExternalSystem())) {
            return "externalSystem不能为空";
        }
        if (!StringUtils.hasText(request.getExternalBizId())) {
            return "externalBizId不能为空";
        }
        if (!StringUtils.hasText(request.getSceneName())) {
            return "sceneName不能为空";
        }
        if (trim(request.getExternalSystem()).length() > 64) {
            return "externalSystem长度不能超过64";
        }
        if (trim(request.getExternalBizId()).length() > 128) {
            return "externalBizId长度不能超过128";
        }
        return null;
    }

    private Astral3DScenes buildExternalScene(ExternalSceneCreateRequest request,
                                              Astral3DScenesExample template,
                                              String externalSystem,
                                              String externalBizId) {
        Astral3DScenes scene = new Astral3DScenes();
        scene.setSceneName(trim(request.getSceneName()));
        scene.setSceneType(resolveString(request.getSceneType(), template == null ? null : template.getSceneType(), DEFAULT_SCENE_TYPE));
        scene.setSceneVersion(1);
        scene.setSceneIntroduction(resolveNullableString(request.getSceneIntroduction(), template == null ? null : template.getSceneIntroduction()));
        scene.setCoverPicture("");
        scene.setHasDrawing(template == null || template.getHasDrawing() == null ? 0 : template.getHasDrawing());
        scene.setZip("");
        scene.setZipSize(resolveString(null, template == null ? null : template.getZipSize(), "0KB"));
        scene.setExampleSceneId(template == null ? null : template.getId());
        scene.setProjectType(resolveInteger(request.getProjectType(), template == null ? null : template.getProjectType(), 0));
        scene.setCesiumConfig(resolveCesiumConfig(request.getCesiumConfig(), template));
        scene.setDelTag(0);
        scene.setSceneSource(SCENE_SOURCE_EXTERNAL);
        scene.setExternalSystem(externalSystem);
        scene.setExternalBizId(externalBizId);
        scene.setExternalMeta(toStoredJson(request.getMeta()));
        return scene;
    }

    private String resolveCesiumConfig(Object requestCesiumConfig, Astral3DScenesExample template) {
        if (requestCesiumConfig != null) {
            return toStoredJson(requestCesiumConfig);
        }
        return template == null ? null : template.getCesiumConfig();
    }

    private Astral3DScenes findExternalScene(String externalSystem, String externalBizId) {
        // 注意：当前管理端删除场景是物理删除，所以这里不需要过滤 delTag。
        // 如果以后 /editor3d/scenes/del 改成逻辑删除（例如 delTag=1 或 @TableLogic），
        // 这里必须同步只查询未删除记录，并重新评估 uk_external_system_biz_id 唯一键策略；
        // 否则同一 externalSystem + externalBizId 可能命中已删除场景，或因唯一键导致无法重新创建。
        return astral3DScenesService.getOne(
                new QueryWrapper<Astral3DScenes>()
                        .eq("externalSystem", externalSystem)
                        .eq("externalBizId", externalBizId)
                        .last("LIMIT 1"),
                false
        );
    }

    private JSONObject toTemplateResponse(Astral3DScenesExample example) {
        JSONObject item = new JSONObject();
        item.put("templateId", example.getId());
        item.put("sceneName", example.getSceneName());
        item.put("sceneType", example.getSceneType());
        item.put("sceneIntroduction", example.getSceneIntroduction());
        item.put("coverPicture", example.getCoverPicture());
        item.put("projectType", example.getProjectType());
        item.put("zipSize", example.getZipSize());
        return item;
    }

    private JSONObject toCreateResponse(Astral3DScenes scene, boolean created) {
        JSONObject item = new JSONObject();
        item.put("sceneId", scene.getId());
        item.put("created", created);
        item.put("sceneSource", scene.getSceneSource());
        item.put("exampleSceneId", scene.getExampleSceneId());
        return item;
    }

    private String resolveString(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return trim(first);
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

    private Integer resolveInteger(Integer first, Integer second, Integer fallback) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return fallback;
    }

    private String toStoredJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return JSON.toJSONString(value);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    @Data
    public static class ExternalSceneCreateRequest {
        private String externalSystem;
        private String externalBizId;
        private String sceneName;
        private String templateId;
        private String sceneType;
        private String sceneIntroduction;
        private Integer projectType;
        private Object cesiumConfig;
        private Object meta;
    }
}
