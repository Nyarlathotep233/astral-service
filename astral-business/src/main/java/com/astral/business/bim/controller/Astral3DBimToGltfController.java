package com.astral.business.bim.controller;

import com.alibaba.fastjson2.JSONObject;
import com.astral.business.bim.entity.RvtConversionRequest;
import com.astral.business.bim.entity.RvtConversionRequestOptions;
import com.astral.common.config.AstralConfig;
import com.astral.common.constant.CommonConstant;
import com.astral.business.bim.entity.RvtConversionResult;
import com.astral.common.result.Result;
import com.astral.common.utils.CommonUtils;
import com.astral.core.config.webSocketConfig.RevitWsClient;
import com.astral.core.config.webSocketConfig.WebSocket;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.astral.business.bim.entity.Astral3DBimToGltf;
import com.astral.business.bim.service.Astral3DBimToGltfService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.LinkOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * BIM模型轻量化(Astral3DBimToGltf)表控制层
 */
@RestController
@RequestMapping("/editor3d/bim2gltf")
public class Astral3DBimToGltfController {
    private static final Logger log = LoggerFactory.getLogger(Astral3DBimToGltfController.class);
    private static final long CONVERSION_WAIT_NANOS = TimeUnit.MINUTES.toNanos(65);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService conversions = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "h3d-bim-conversion");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 服务对象
     */
    @Autowired
    private Astral3DBimToGltfService astral3DEditorBimToGltfService;

    @Autowired
    private WebSocket wsocket;

    private RevitWsClient revitWs;

    @Value("${dev.currentAbPath}")
    private String _appPath;

    @Value("${revit.address}")
    private String address;

    @Value("${revit.port}")
    private String port;

    @Value("${revit.path}")
    private String path;

    @Value("${revit.timeout}")
    private int timeout;

    @PostConstruct
    public void init() {
        revitWs = new RevitWsClient(address, port, path, timeout);
        revitWs.start();
    }

    @PreDestroy
    public void stop() {
        if (revitWs != null) revitWs.stop();
        for (Runnable queued : conversions.shutdownNow()) {
            failConversion((ConversionTask) queued, "服务停止，转换中断");
        }
    }

    @PostMapping("/add")
    public Result<?> post(@RequestBody Astral3DBimToGltf lb3dEditorBimToGltf) {
        return Result.toAjax(astral3DEditorBimToGltfService.save(lb3dEditorBimToGltf));
    }

    @PostMapping("/addAndConversion")
    public Result<?> addAndConversion(HttpServletRequest request, @RequestBody Map<String, Object> reqMap) {
        try {
            String json = JSONObject.toJSONString(reqMap);
            RvtConversionRequest conversion = JSONObject.parseObject(json, RvtConversionRequest.class);
            Astral3DBimToGltf item = JSONObject.parseObject(json, Astral3DBimToGltf.class);
            if (conversion.getOptions() != null) {
                conversion.getOptions().setUseDraco(true);
                item.getOptions().put("useDraco", true);
            }
            item.setGltfFilePath("");
            item.setConversionStatus(0);
            if (!astral3DEditorBimToGltfService.save(item)) return Result.error("保存失败");

            ConversionTask task = new ConversionTask(item, conversion.getOptions(), request.getParameter("uname"));
            try {
                conversions.execute(task);
            } catch (RejectedExecutionException e) {
                failConversion(task, "转换服务正在停止");
                return Result.error("转换服务正在停止");
            }
            return Result.success(item);
        } catch (Exception e) {
            log.warn("BIM conversion request failed: {}", e.getClass().getSimpleName());
            return Result.error(e.getMessage());
        }
    }

    private final class ConversionTask implements Runnable {
        final Astral3DBimToGltf item;
        final RvtConversionRequestOptions options;
        final String subscriber;
        final AtomicBoolean terminal = new AtomicBoolean();

        ConversionTask(Astral3DBimToGltf item, RvtConversionRequestOptions options, String subscriber) {
            this.item = item;
            this.options = options;
            this.subscriber = subscriber;
        }

        @Override
        public void run() {
            convert(this);
        }
    }

    private void convert(ConversionTask task) {
        String archiveDirectory = null;
        Path product = null;
        boolean committed = false;
        try {
            Path source = resolveSource(task.item.getBimFilePath());
            RvtConversionRequest message = new RvtConversionRequest();
            message.setFileId(task.item.getId());
            message.setFilePath(source.toString());
            message.setOptions(task.options);
            if (task.options == null) throw new IOException("转换配置为空");
            task.options.setUseDraco(true);

            long connection = revitWs.sendMsg(JSONObject.toJSONString(message));
            long deadline = System.nanoTime() + CONVERSION_WAIT_NANOS;
            while (true) {
                JsonNode reply = objectMapper.readTree(awaitMessage(deadline, connection));
                if (!isReplyFor(reply, message.getFileId(), message.getFilePath())) continue;
                String type = reply.path("type").asText();
                if ("progress".equals(type)) {
                    sendUpdate(task, "progress", decimal(reply, "progress"), null);
                } else if ("failed".equals(type)) {
                    String reason = reply.path("message").asText("");
                    throw new IOException(StringUtils.hasText(reason) ? reason : "RVT 转换失败");
                } else if ("completed".equals(type)) {
                    BigDecimal seconds = decimal(reply, "runSeconds");
                    product = resolveProduct(source, reply.path("gltfPath").asText());
                    // Each archive directory belongs only to this attempt, including partial upload failures.
                    String candidate = "upload/bim/bim2gltf/" + UUID.randomUUID();
                    prepareArchive(candidate);
                    archiveDirectory = candidate;
                    String archivePath = CommonUtils.upload(archiveDirectory, product.toFile(), false);
                    task.item.setGltfFilePath(archivePath);
                    task.item.setGltfFileSize(BigDecimal.valueOf(Files.size(product)));
                    task.item.setConversionDuration(seconds);
                    task.item.setConversionStatus(1);
                    if (!astral3DEditorBimToGltfService.updateById(task.item)) {
                        throw new IOException("转换产物记录更新失败");
                    }
                    committed = true;
                    task.terminal.set(true);
                    sendUpdate(task, "completed", BigDecimal.valueOf(100), null);
                    try {
                        Files.deleteIfExists(product);
                    } catch (IOException e) {
                        log.warn("BIM {} completed; temporary product cleanup failed", task.item.getId());
                    }
                    return;
                } else {
                    throw new IOException("转换服务返回未知消息类型");
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (committed) {
                log.error("BIM {} completed; post-completion operation failed", task.item.getId());
                return;
            }
            String reason = e instanceof InterruptedException ? "服务停止，转换中断" : e.getMessage();
            if (archiveDirectory != null) {
                try {
                    rollbackArchive(archiveDirectory, product.getFileName().toString());
                } catch (Exception rollbackError) {
                    log.error("BIM {} archive rollback failed: {}: {}", task.item.getId(), rollbackError.getClass().getSimpleName(), logReason(rollbackError.getMessage()));
                    reason = (reason == null ? "转换失败" : reason) + "；产物归档回滚失败";
                }
            }
            log.warn("BIM {} conversion failed: {}: {}", task.item.getId(), e.getClass().getSimpleName(), logReason(reason));
            failConversion(task, StringUtils.hasText(reason) ? reason : "RVT 转换失败");
        }
    }

    private String awaitMessage(long deadline, long connection) throws IOException, InterruptedException {
        while (true) {
            if (!revitWs.isConnected() || revitWs.getConnectionVersion() != connection) {
                throw new IOException("Revit 转换服务连接已断开，本次转换未完成");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Revit 转换等待超过 65 分钟");
            String message = revitWs.readMsg(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
            if (message != null) return message;
        }
    }

    private String logReason(String reason) {
        if (reason == null) return "No error message";
        String redacted = reason.replaceAll("(?i)[a-z]:[\\\\/][^\\r\\n]*", "[local path]");
        return redacted.replaceAll("(?i)(password|token|secret|authorization|api[-_]?key)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]");
    }

    private static boolean isReplyFor(JsonNode reply, Long fileId, String filePath) throws IOException {
        if (reply == null || !reply.path("fileId").isIntegralNumber() || !reply.path("fileId").canConvertToLong()
                || !reply.path("filePath").isTextual()) {
            throw new IOException("转换响应缺少有效的 fileId 或 filePath");
        }
        if (reply.get("fileId").longValue() != fileId.longValue()) return false;
        if (!filePath.equals(reply.get("filePath").textValue())) throw new IOException("转换响应文件路径不匹配");
        return true;
    }

    private static BigDecimal decimal(JsonNode reply, String field) throws IOException {
        JsonNode value = reply.get(field);
        if (value == null || (!value.isNumber() && !value.isTextual())) throw new IOException("转换响应数值无效: " + field);
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            throw new IOException("转换响应数值无效: " + field);
        }
    }

    private Path resolveSource(String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) throw new IOException("RVT 源文件路径为空");
        Path root = Paths.get(_appPath).toAbsolutePath().normalize();
        Path sourceRoot = root.resolve("bim-sources").toRealPath();
        Path relative = Paths.get(relativePath);
        Path source = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !source.startsWith(root.resolve("bim-sources"))
                || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rvt")
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("RVT 源文件路径无效");
        }
        source = source.toRealPath();
        if (!sourceRoot.startsWith(root.toRealPath()) || !source.startsWith(sourceRoot)) throw new IOException("RVT 源文件不在源文件目录");
        return source;
    }

    private static Path resolveProduct(Path source, String fileName) throws IOException {
        if (!StringUtils.hasText(fileName) || fileName.contains("/") || fileName.contains("\\") || fileName.contains(":")
                || !fileName.toLowerCase(Locale.ROOT).endsWith(".glb")) throw new IOException("转换产物文件名无效");
        Path product = source.getParent().resolve(fileName).normalize();
        if (!Files.isRegularFile(product, LinkOption.NOFOLLOW_LINKS)
                || !product.toRealPath().getParent().equals(source.getParent())) throw new IOException("转换产物不存在或路径无效");
        return product.toRealPath();
    }

    private void prepareArchive(String directory) throws IOException {
        if (CommonConstant.UPLOAD_TYPE_UPYUN.equals(AstralConfig.getUploadType())) return;
        Path root = Paths.get(AstralConfig.getUploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path archive = root.resolve(directory).normalize();
        Files.createDirectories(archive.getParent());
        if (!archive.getParent().toRealPath().startsWith(root.toRealPath())) throw new IOException("产物归档目录无效");
        Files.createDirectory(archive);
    }

    private void rollbackArchive(String directory, String fileName) throws IOException {
        if (CommonConstant.UPLOAD_TYPE_UPYUN.equals(AstralConfig.getUploadType())) {
            if (!CommonUtils.deleteFile(directory)) throw new IOException("产物归档回滚失败");
            return;
        }
        Path uploadRoot = Paths.get(AstralConfig.getUploadDir()).toAbsolutePath().normalize();
        Path archive = uploadRoot.resolve(directory).normalize();
        Path file = archive.resolve(fileName).normalize();
        if (!archive.startsWith(uploadRoot.resolve("upload/bim/bim2gltf")) || !file.getParent().equals(archive)) {
            throw new IOException("产物回滚路径无效");
        }
        Files.deleteIfExists(file);
        Files.deleteIfExists(archive);
    }

    private void failConversion(ConversionTask task, String reason) {
        if (!task.terminal.compareAndSet(false, true)) return;
        task.item.setConversionStatus(2);
        task.item.setGltfFilePath("");
        task.item.setGltfFileSize(BigDecimal.ZERO);
        task.item.setConversionDuration(BigDecimal.ZERO);
        try {
            if (!astral3DEditorBimToGltfService.updateById(task.item)) throw new IllegalStateException("状态更新失败");
        } catch (Exception e) {
            reason += "；失败状态未能写入数据库";
            log.error("BIM {} failure status update failed: {}: {}", task.item.getId(), e.getClass().getSimpleName(), logReason(e.getMessage()));
        }
        sendUpdate(task, "failed", BigDecimal.ZERO, reason);
    }

    private void sendUpdate(ConversionTask task, String status, BigDecimal progress, String message) {
        JSONObject event = new JSONObject();
        event.put("type", "bim2gltf");
        event.put("subscriber", task.subscriber);
        event.put("data", new RvtConversionResult(status, task.item, progress, message));
        try {
            wsocket.sendMessage(event.toJSONString());
        } catch (Exception e) {
            log.warn("BIM {} notification failed: {}", task.item.getId(), e.getClass().getSimpleName());
        }
    }

    @GetMapping("/get/{id}")
    public Result<?> getOne(@PathVariable("id") String id) {
        try {
            return Result.success(astral3DEditorBimToGltfService.getById(id));
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
        QueryWrapper<Astral3DBimToGltf> queryWrapper = new QueryWrapper<Astral3DBimToGltf>();
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
        Page<Astral3DBimToGltf> page = new Page<>();
        page.setSize(limit);
        page.setCurrent(offset / limit + 1);
        if (!CollectionUtils.isEmpty(fields)) {
            queryWrapper.select(fields);
        }
        try {
            Page<Astral3DBimToGltf> resultPage = astral3DEditorBimToGltfService.page(page, queryWrapper);
            long count = astral3DEditorBimToGltfService.count(queryWrapper);
            JSONObject result = new JSONObject();
            result.put("items", resultPage.getRecords());
            result.put("current", offset + 1);
            result.put("pageSize", limit);
            result.put("pages", (count + limit - 1) / limit);
            result.put("total", count);
            return Result.success(result);
//            return Result.success(astral3DEditorBimToGltfService.page(page, queryWrapper));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{id}")
    public Result<?> put(@PathVariable("id") Long id, @RequestBody Astral3DBimToGltf lb3dEditorBimToGltf) {
        lb3dEditorBimToGltf.setId(id);
        return Result.toAjax(astral3DEditorBimToGltfService.updateById(lb3dEditorBimToGltf));
    }

    @DeleteMapping("/del/{id}")
    public Result<?> delete(@PathVariable("id") Long id) {
        return Result.toAjax(astral3DEditorBimToGltfService.removeById(id));
    }

    @PostMapping("/uploadRvt")
    public Result<?> doRvtUpload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.error("未获取到上传文件！");
        try {
            String fileName = safeSourceName(file.getOriginalFilename());
            Path appRoot = Paths.get(_appPath).toAbsolutePath().normalize().toRealPath();
            Path sourceRoot = appRoot.resolve("bim-sources");
            Files.createDirectories(sourceRoot);
            if (!sourceRoot.toRealPath().startsWith(appRoot)) throw new IOException("RVT 源文件目录无效");
            String directory = UUID.randomUUID().toString();
            Path sourceDirectory = Files.createDirectory(sourceRoot.resolve(directory));
            Path target = sourceDirectory.resolve(fileName);
            try (java.io.InputStream input = file.getInputStream()) {
                Files.copy(input, target);
            } catch (IOException e) {
                Files.deleteIfExists(target);
                Files.deleteIfExists(sourceDirectory);
                throw e;
            }
            return Result.success("上传成功", "bim-sources/" + directory + "/" + fileName);
        } catch (Exception e) {
            log.warn("RVT source upload failed: {}", e.getClass().getSimpleName());
            return Result.error(e.getMessage());
        }
    }

    private static String safeSourceName(String original) throws IOException {
        if (!StringUtils.hasText(original)) throw new IOException("未获取到文件名");
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".rvt")) throw new IOException("只能上传rvt文件！");
        name = name.replaceAll("[<>:|?*\\p{Cntrl}]", "_").replace('"', '_');
        if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])\\..*")) name = "_" + name;
        return name;
    }
}
