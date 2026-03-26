package com.astral.business.ai.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.astral.common.result.Result;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编辑器 AI 能力（真实模型）
 */
@RestController
@RequestMapping("/editor3d/ai")
public class Astral3DAiController {
    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>(.*?)</think>");
    private static final int DEFAULT_TIMEOUT_MS = 90000;
    private static final int MIN_TIMEOUT_MS = 5000;
    private static final int MAX_TIMEOUT_MS = 300000;


    @Value("${astral.ai.script.provider:openai-compatible}")
    private String aiProvider;

    @Value("${astral.ai.script.endpoint:https://api.openai.com/v1/chat/completions}")
    private String aiEndpoint;

    @Value("${astral.ai.script.apiKey:}")
    private String aiApiKey;

    @Value("${astral.ai.script.model:gpt-4o-mini}")
    private String aiModel;

    @Value("${astral.ai.script.timeoutMs:90000}")
    private Integer aiTimeoutMs;

    @Value("${astral.ai.script.temperature:0.2}")
    private Double aiTemperature;

    @GetMapping("/script/status")
    public Result<?> getScriptStatus() {
        ScriptStatusResponse response = new ScriptStatusResponse();
        boolean liveReady = isLiveReady();

        response.setProvider(safeText(aiProvider));
        response.setLiveReady(liveReady);
        response.setModel(aiModel);
        response.setEndpoint(maskEndpoint(aiEndpoint));
        response.setMessage(buildStatusMessage(liveReady));
        return Result.success(response);
    }

    @PostMapping("/script/generate")
    public Result<?> generateScript(@RequestBody ScriptRequest request) {
        if (request == null || !StringUtils.hasText(request.getRequirement())) {
            return Result.error("需求不能为空");
        }

        RuntimeAiConfig runtimeAiConfig = resolveRuntimeAiConfig(request);
        if (!isLiveReady(runtimeAiConfig)) {
            return Result.error(buildRuntimeNotReadyMessage(runtimeAiConfig));
        }

        try {
            ScriptResponse response = generateByLiveModel(request, runtimeAiConfig);
            return Result.success(response);
        } catch (Exception ex) {
            return Result.error("AI 生成失败：" + shortenMessage(ex.getMessage()));
        }
    }

    private boolean isLiveProvider() {
        return "openai".equalsIgnoreCase(aiProvider) || "openai-compatible".equalsIgnoreCase(aiProvider);
    }

    private boolean isLiveProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            return false;
        }
        return "openai".equalsIgnoreCase(provider) || "openai-compatible".equalsIgnoreCase(provider);
    }

    private boolean isLiveReady() {
        return isLiveProvider() && StringUtils.hasText(aiApiKey);
    }

    private boolean isLiveReady(RuntimeAiConfig runtimeAiConfig) {
        return runtimeAiConfig != null
                && isLiveProvider(runtimeAiConfig.getProvider())
                && StringUtils.hasText(runtimeAiConfig.getApiKey());
    }

    private RuntimeAiConfig resolveRuntimeAiConfig(ScriptRequest request) {
        RuntimeAiConfig cfg = new RuntimeAiConfig();
        cfg.setProvider(firstNonBlank(request == null ? null : request.getProvider(), aiProvider));
        cfg.setEndpoint(firstNonBlank(request == null ? null : request.getEndpoint(), aiEndpoint));
        cfg.setApiKey(firstNonBlank(request == null ? null : request.getApiKey(), aiApiKey));
        cfg.setModel(firstNonBlank(request == null ? null : request.getModel(), aiModel));
        cfg.setTemperature(firstNonNull(request == null ? null : request.getTemperature(), aiTemperature == null ? 0.2 : aiTemperature));
        cfg.setTimeoutMs(resolveTimeoutMs(request == null ? null : request.getTimeoutMs()));
        return cfg;
    }

    private int resolveTimeoutMs(Integer requestTimeout) {
        int timeout = requestTimeout != null ? requestTimeout : (aiTimeoutMs == null ? DEFAULT_TIMEOUT_MS : aiTimeoutMs);
        if (timeout < MIN_TIMEOUT_MS) {
            return MIN_TIMEOUT_MS;
        }
        if (timeout > MAX_TIMEOUT_MS) {
            return MAX_TIMEOUT_MS;
        }
        return timeout;
    }

    private String firstNonBlank(String requestValue, String defaultValue) {
        if (StringUtils.hasText(requestValue)) {
            return requestValue.trim();
        }
        return defaultValue == null ? "" : defaultValue.trim();
    }

    private <T> T firstNonNull(T requestValue, T defaultValue) {
        return requestValue != null ? requestValue : defaultValue;
    }

    private String buildStatusMessage(boolean liveReady) {
        if (liveReady) {
            return "已配置 API Key，将调用真实模型。";
        }
        if (!isLiveProvider()) {
            return "provider 不受支持，请设置为 openai 或 openai-compatible。";
        }
        return "provider 已设为真实模型，但 API Key 未配置。";
    }

    private String buildRuntimeNotReadyMessage(RuntimeAiConfig runtimeAiConfig) {
        if (runtimeAiConfig == null) {
            return "AI 配置缺失。";
        }
        if (!isLiveProvider(runtimeAiConfig.getProvider())) {
            return "provider 不受支持，请使用 openai 或 openai-compatible。";
        }
        if (!StringUtils.hasText(runtimeAiConfig.getApiKey())) {
            return "未配置可用 API Key。";
        }
        if (!StringUtils.hasText(runtimeAiConfig.getEndpoint())) {
            return "未配置 endpoint。";
        }
        if (!StringUtils.hasText(runtimeAiConfig.getModel())) {
            return "未配置 model。";
        }
        return "AI 配置无效。";
    }

    private String maskEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return "";
        }
        String normalized = endpoint.trim();
        int queryIdx = normalized.indexOf("?");
        if (queryIdx > 0) {
            return normalized.substring(0, queryIdx);
        }
        return normalized;
    }

    private ScriptResponse generateByLiveModel(ScriptRequest request, RuntimeAiConfig runtimeAiConfig) {
        JSONObject payload = new JSONObject();
        payload.put("model", runtimeAiConfig.getModel());
        payload.put("temperature", runtimeAiConfig.getTemperature());

        JSONArray messages = new JSONArray();
        messages.add(createMessage("system", buildSystemPrompt()));
        messages.add(createMessage("user", buildUserPrompt(request)));
        payload.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(runtimeAiConfig.getApiKey().trim());

        HttpEntity<String> httpEntity = new HttpEntity<>(payload.toJSONString(), headers);
        ResponseEntity<String> rawResponse = createRestTemplate(runtimeAiConfig.getTimeoutMs())
                .postForEntity(runtimeAiConfig.getEndpoint(), httpEntity, String.class);

        if (!rawResponse.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("HTTP " + rawResponse.getStatusCodeValue());
        }

        String content = extractMessageContent(rawResponse.getBody());
        GeneratedScriptContent generated = parseGeneratedScript(content);
        if (!StringUtils.hasText(generated.getSource())) {
            throw new IllegalStateException("模型返回的 source 为空");
        }

        ScriptResponse response = new ScriptResponse();
        response.setRequestId(UUID.randomUUID().toString());
        response.setProvider("live");
        response.setModel(runtimeAiConfig.getModel());
        response.setScriptName(getScriptName(request));
        response.setSource(generated.getSource());
        response.setExplanation(StringUtils.hasText(generated.getExplanation()) ? generated.getExplanation() : buildExplanation(request));
        response.setThinking(generated.getThinking());

        List<String> warnings = new ArrayList<>();
        warnings.add("请在应用脚本前检查对象名称、生命周期函数与场景命名是否一致。");
        if (generated.getWarnings() != null && !generated.getWarnings().isEmpty()) {
            warnings.addAll(generated.getWarnings());
        }
        response.setWarnings(warnings);

        return response;
    }

    private RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return new RestTemplate(requestFactory);
    }

    private JSONObject createMessage(String role, String content) {
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private String buildSystemPrompt() {
        return "你是 3D 编辑器脚本助手，请按用户需求生成可直接运行的 JavaScript 脚本。"
                + "输出必须是 JSON 对象，不要 markdown 代码块。"
                + "JSON 格式：{\"source\":\"...\",\"explanation\":\"...\",\"warnings\":[\"...\"]}。"
                + "运行时约束：afterAnimation 签名为 (delta, toBeRender)，键盘事件用 onKeyDown(event)。"
                + "可用内置变量：this, THREE, helper, renderer, scene, camera, controls, timer。"
                + "若包含连续动画，请在 afterAnimation 中调用 toBeRender(true)。"
                + "不要虚构不存在的 API、生命周期或参数。"
                + "尽量在 currentSource 基础上最小改动，避免无关重写。";
    }

    private String buildUserPrompt(ScriptRequest request) {
        String requirement = safeText(request.getRequirement());
        StringBuilder sb = new StringBuilder();
        sb.append("需求：").append(requirement).append("\n");
        sb.append("对象名称：").append(safeText(request.getObjectName())).append("\n");
        sb.append("对象类型：").append(safeText(request.getObjectType())).append("\n");
        sb.append("脚本名称：").append(safeText(getScriptName(request))).append("\n");
        sb.append("脚本模式：").append(safeText(request.getMode())).append("\n");
        sb.append("脚本 API 速查（精简）：\n");
        sb.append("- 内置变量：this, THREE, helper, renderer, scene, camera, controls, timer\n");
        sb.append("- 内置方法：render(delta?)\n");
        sb.append("- 生命周期：loaded(); beforeAnimation(delta); afterAnimation(delta,toBeRender); beforeRender(delta); afterRender(delta); beforeDestroy(); afterDestroy();\n");
        sb.append("- 常用事件：onPick/onDoubleClick/onKeyDown/onKeyUp/onPointerDown/onPointerUp/onPointerMove/onTouchStart/onTouchEnd\n");
        sb.append("- helper：new helper.Animation(this); helper.objectByUuid(uuid)\n");
        sb.append("生命周期签名提示：loaded() / afterAnimation(delta,toBeRender) / onKeyDown(event)\n");
        sb.append("建议旋转写法：const step = THREE.MathUtils.degToRad(speedDegPerSec) * delta;\n");
        sb.append("建议最小模板：\n");
        sb.append("let target = null;\n");
        sb.append("function loaded(){ target = scene.getObjectByName(\"").append(escapeJsString(request.getObjectName())).append("\") || this; }\n");
        sb.append("function afterAnimation(delta, toBeRender){ /* 更新逻辑 */ toBeRender(true); }\n");
        sb.append("function onKeyDown(event){ /* 键盘交互 */ }\n");
        sb.append("当前脚本（可在其基础上修改）：\n");
        sb.append(limitText(request.getCurrentSource(), 16000)).append("\n");
        sb.append("要求：尽量保持当前脚本结构，避免无关改动；不要使用 window/globalThis 保存状态。");
        return sb.toString();
    }

    private String extractMessageContent(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new IllegalStateException("响应体为空");
        }
        JSONObject root = JSONObject.parseObject(responseBody);
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("choices 为空");
        }

        JSONObject first = choices.getJSONObject(0);
        if (first == null) {
            throw new IllegalStateException("choice[0] 为空");
        }
        JSONObject message = first.getJSONObject("message");
        if (message == null) {
            throw new IllegalStateException("message 为空");
        }
        String content = message.getString("content");
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("message.content 为空");
        }
        return content;
    }

    private GeneratedScriptContent parseGeneratedScript(String rawContent) {
        GeneratedScriptContent generated = new GeneratedScriptContent();
        generated.setWarnings(new ArrayList<String>());

        String content = StringUtils.hasText(rawContent) ? rawContent.trim() : "";
        if (!StringUtils.hasText(content)) {
            return generated;
        }

        ThinkExtraction thinkExtraction = extractThink(content);
        if (StringUtils.hasText(thinkExtraction.getThinking())) {
            generated.setThinking(thinkExtraction.getThinking());
        }

        content = StringUtils.hasText(thinkExtraction.getContent()) ? thinkExtraction.getContent() : content;
        JSONObject json = parseJsonObject(content);
        if (json != null) {
            generated.setSource(json.getString("source"));
            generated.setExplanation(json.getString("explanation"));
            if (!StringUtils.hasText(generated.getThinking())) {
                generated.setThinking(json.getString("thinking"));
            }

            JSONArray warnings = json.getJSONArray("warnings");
            if (warnings != null && !warnings.isEmpty()) {
                List<String> warningList = new ArrayList<>();
                for (int i = 0; i < warnings.size(); i++) {
                    String warning = warnings.getString(i);
                    if (StringUtils.hasText(warning)) {
                        warningList.add(warning.trim());
                    }
                }
                generated.setWarnings(warningList);
            }
            return generated;
        }

        generated.setSource(stripCodeFence(content));
        generated.setExplanation("模型返回非 JSON，已按纯代码内容直接应用。");
        generated.getWarnings().add("建议补充结构化输出约束，避免结果波动。");
        return generated;
    }

    private JSONObject parseJsonObject(String content) {
        String normalized = stripCodeFence(content);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        JSONObject direct = tryParseObject(normalized.trim());
        if (direct != null) {
            return direct;
        }

        // case: 模型返回了“JSON 字符串”而不是 JSON 对象
        Object parsed = tryParseGenericJson(normalized.trim());
        if (parsed instanceof JSONObject) {
            return (JSONObject) parsed;
        }
        if (parsed instanceof String) {
            String nested = stripCodeFence(((String) parsed).trim());
            JSONObject nestedObj = tryParseObject(nested);
            if (nestedObj != null) {
                return nestedObj;
            }
            JSONObject anchoredNested = parseJsonBySourceAnchor(nested);
            if (anchoredNested != null) {
                return anchoredNested;
            }
        }

        JSONObject anchored = parseJsonBySourceAnchor(normalized);
        if (anchored != null) {
            return anchored;
        }

        // 兼容部分模型返回：{\"source\":\"...\"}
        if (normalized.contains("\\\"source\\\"")) {
            String unescaped = normalized
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
            JSONObject unescapedObj = tryParseObject(unescaped);
            if (unescapedObj != null) {
                return unescapedObj;
            }
        }

        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String sub = normalized.substring(start, end + 1);
            return tryParseObject(sub);
        }
        return null;
    }

    private JSONObject tryParseObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return JSONObject.parseObject(raw);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Object tryParseGenericJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch (Exception ignore) {
            return null;
        }
    }

    private JSONObject parseJsonBySourceAnchor(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        String normalized = text.trim();
        int sourceKeyIdx = normalized.indexOf("\"source\"");
        if (sourceKeyIdx < 0) {
            return null;
        }

        int start = normalized.lastIndexOf('{', sourceKeyIdx);
        if (start < 0) {
            return null;
        }

        for (int end = normalized.lastIndexOf('}'); end > sourceKeyIdx; end = normalized.lastIndexOf('}', end - 1)) {
            String candidate = normalized.substring(start, end + 1);
            JSONObject candidateObj = tryParseObject(candidate);
            if (candidateObj != null && candidateObj.containsKey("source")) {
                return candidateObj;
            }
        }
        return null;
    }

    private ThinkExtraction extractThink(String content) {
        ThinkExtraction extraction = new ThinkExtraction();
        extraction.content = content;
        extraction.thinking = "";

        if (!StringUtils.hasText(content)) {
            return extraction;
        }

        Matcher matcher = THINK_BLOCK_PATTERN.matcher(content);
        StringBuilder thinking = new StringBuilder();
        StringBuffer cleaned = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String block = matcher.group(1);
            if (StringUtils.hasText(block)) {
                if (thinking.length() > 0) {
                    thinking.append("\n\n");
                }
                thinking.append(block.trim());
            }
            matcher.appendReplacement(cleaned, "");
        }
        matcher.appendTail(cleaned);

        if (found) {
            extraction.thinking = thinking.toString().trim();
            extraction.content = cleaned.toString().trim();
            return extraction;
        }

        // 部分模型可能只输出起始 think 标签，做兜底清洗
        String normalized = content.trim();
        if (normalized.toLowerCase().startsWith("<think>")) {
            int endIdx = normalized.toLowerCase().lastIndexOf("</think>");
            if (endIdx > 0) {
                String thinkPart = normalized.substring("<think>".length(), endIdx).trim();
                String remainPart = normalized.substring(endIdx + "</think>".length()).trim();
                extraction.thinking = thinkPart;
                extraction.content = remainPart;
            }
        }
        return extraction;
    }

    private String stripCodeFence(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed.replace("```", "").trim();
        }

        int lastFence = trimmed.lastIndexOf("```");
        if (lastFence <= firstLineEnd) {
            return trimmed.substring(firstLineEnd + 1).trim();
        }
        return trimmed.substring(firstLineEnd + 1, lastFence).trim();
    }

    private String shortenMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "未知错误";
        }
        String text = message.trim();
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 120) + "...";
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String escapeJsString(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private String limitText(String text, int maxLength) {
        if (!StringUtils.hasText(text) || maxLength <= 0) {
            return "";
        }
        String normalized = text.replace("\r", "");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "\n/* ... truncated ... */";
    }

    private String getScriptName(ScriptRequest request) {
        if (request != null && StringUtils.hasText(request.getScriptName())) {
            return request.getScriptName();
        }
        return "AI_Generated_Script";
    }

    private String buildExplanation(ScriptRequest request) {
        String object = StringUtils.hasText(request.getObjectName()) ? request.getObjectName() : "当前对象";
        return "已为对象 " + object + " 生成脚本，请在应用前检查对象名称、生命周期函数和场景逻辑。";
    }

    @Data
    public static class ScriptRequest {
        private String sceneId;
        private String objectId;
        private String objectName;
        private String objectType;
        private String scriptName;
        private String currentSource;
        private String requirement;
        private String mode;
        private String provider;
        private String endpoint;
        private String apiKey;
        private String model;
        private Integer timeoutMs;
        private Double temperature;
    }

    @Data
    public static class ScriptResponse {
        private String requestId;
        private String provider;
        private String model;
        private String scriptName;
        private String source;
        private String explanation;
        private List<String> warnings;
        private String thinking;
    }

    @Data
    public static class ScriptStatusResponse {
        private String provider;
        private boolean liveReady;
        private String model;
        private String endpoint;
        private String message;
    }

    @Data
    public static class GeneratedScriptContent {
        private String source;
        private String explanation;
        private List<String> warnings;
        private String thinking;
    }

    @Data
    public static class ThinkExtraction {
        private String content;
        private String thinking;
    }

    @Data
    public static class RuntimeAiConfig {
        private String provider;
        private String endpoint;
        private String apiKey;
        private String model;
        private Integer timeoutMs;
        private Double temperature;
    }
}
