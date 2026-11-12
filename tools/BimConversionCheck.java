import com.alibaba.fastjson2.JSONObject;
import com.astral.business.bim.controller.Astral3DBimToGltfController;
import com.astral.business.bim.entity.Astral3DBimToGltf;
import com.astral.business.bim.service.Astral3DBimToGltfService;
import com.astral.common.config.AstralConfig;
import com.astral.common.result.Result;
import com.astral.core.config.webSocketConfig.RevitWsClient;
import com.astral.core.config.webSocketConfig.WebSocket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone checks: synthetic files and in-memory DB/WS doubles only; never starts Revit or a backend. */
public class BimConversionCheck {
    private static final AtomicInteger checks = new AtomicInteger();
    private static final byte[] SOURCE = "synthetic-rvt-test-input".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PRODUCT = "synthetic-glb-test-output".getBytes(StandardCharsets.UTF_8);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks.incrementAndGet();
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = target == null ? Astral3DBimToGltfController.class : target.getClass();
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks.incrementAndGet();
        }
    }

    private static class MemoryFile implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return "C:\\fakepath\\model?.rvt"; }
        public String getContentType() { return "application/octet-stream"; }
        public boolean isEmpty() { return false; }
        public long getSize() { return SOURCE.length; }
        public byte[] getBytes() { return SOURCE.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(SOURCE); }
        public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), SOURCE); }
    }

    private static class FakeRevit extends RevitWsClient {
        volatile boolean connected = true;
        volatile boolean failSend;
        volatile long version = 1;
        final BlockingQueue<JSONObject> sent = new LinkedBlockingQueue<>();
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

        FakeRevit() { super("127.0.0.1", "1", "/not-started", 1000); }
        @Override public boolean isConnected() { return connected; }
        @Override public long getConnectionVersion() { return version; }
        @Override public long sendMsg(String message) throws IOException {
            if (failSend) throw new IOException("injected send failure");
            sent.add(JSONObject.parseObject(message));
            return version;
        }
        @Override public String readMsg(long wait, TimeUnit unit) throws InterruptedException { return replies.poll(wait, unit); }
        @Override public void stop() { connected = false; version++; super.stop(); }

        JSONObject request() throws Exception {
            JSONObject request = sent.poll(5, TimeUnit.SECONDS);
            check(request != null, "Expected conversion request");
            return request;
        }

        void reply(JSONObject request, String type, Object... values) {
            JSONObject reply = new JSONObject();
            reply.put("fileId", request.getLong("fileId"));
            reply.put("filePath", request.getString("filePath"));
            reply.put("type", type);
            for (int i = 0; i < values.length; i += 2) reply.put((String) values[i], values[i + 1]);
            replies.add(reply.toJSONString());
        }
    }

    private static class CaptureSocket extends WebSocket {
        final List<JSONObject> events = new CopyOnWriteArrayList<>();
        final Set<Long> committed;
        CaptureSocket(Set<Long> committed) { this.committed = committed; }
        @Override public void sendMessage(String message) {
            JSONObject event = JSONObject.parseObject(message).getJSONObject("data");
            if ("completed".equals(event.getString("conversionStatus"))) {
                check(committed.contains(event.getJSONObject("item").getLong("id")), "Completion preceded DB commit");
            }
            events.add(event);
        }
        JSONObject await(long id, String status) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                for (JSONObject event : events) {
                    if (event.getJSONObject("item").getLongValue("id") == id && status.equals(event.getString("conversionStatus"))) return event;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Missing " + status + " event for " + id);
        }
        void terminalOnce(long id) {
            long count = events.stream().filter(e -> e.getJSONObject("item").getLongValue("id") == id)
                    .filter(e -> !"progress".equals(e.getString("conversionStatus"))).count();
            check(count == 1, "Expected exactly one terminal event for " + id);
        }
    }

    private static class Fixture implements AutoCloseable {
        final Path root;
        final Path uploads;
        final FakeRevit revit = new FakeRevit();
        final Set<Long> committed = ConcurrentHashMap.newKeySet();
        final CaptureSocket socket = new CaptureSocket(committed);
        final AtomicLong ids = new AtomicLong();
        final AtomicBoolean rejectSuccess = new AtomicBoolean();
        final Astral3DBimToGltfController controller = new Astral3DBimToGltfController();

        Fixture(Path root, Path uploads) throws Exception {
            this.root = root;
            this.uploads = uploads;
            field(controller, "_appPath", root.toString());
            field(controller, "revitWs", revit);
            field(controller, "wsocket", socket);
            Astral3DBimToGltfService database = (Astral3DBimToGltfService) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Astral3DBimToGltfService.class}, (proxy, method, args) -> {
                        Astral3DBimToGltf item = (Astral3DBimToGltf) args[0];
                        if ("save".equals(method.getName())) { item.setId(ids.incrementAndGet()); return true; }
                        if ("updateById".equals(method.getName())) {
                            if (item.getConversionStatus() == 1) {
                                if (rejectSuccess.getAndSet(false)) return false;
                                check(Files.isRegularFile(uploads.resolve(item.getGltfFilePath())), "DB success references a missing archive");
                                committed.add(item.getId());
                            }
                            return true;
                        }
                        throw new AssertionError("Unexpected database access: " + method.getName());
                    });
            field(controller, "astral3DEditorBimToGltfService", database);
        }

        Astral3DBimToGltf submit(String relativePath) {
            Map<String, Object> request = new HashMap<>();
            request.put("bimFilePath", relativePath);
            request.put("options", Collections.singletonMap("coordinateReference", "Origin"));
            HttpServletRequest http = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                        if ("getParameter".equals(method.getName())) return "test-user";
                        throw new AssertionError("Unexpected HTTP access: " + method.getName());
                    });
            Result<?> result = controller.addAndConversion(http, request);
            check(result.getCode() == 200, "Expected accepted conversion request");
            return (Astral3DBimToGltf) result.getResult();
        }

        void complete(JSONObject request) throws IOException {
            Path file = Paths.get(request.getString("filePath")).getParent().resolve("product.glb");
            Files.write(file, PRODUCT);
            revit.reply(request, "completed", "gltfPath", "product.glb", "runSeconds", 0.25);
        }
        public void close() { controller.stop(); }
    }

    private static void protocolChecks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Class<?>[] numberTypes = {JsonNode.class, String.class};
        check(new BigDecimal("12").equals(invoke(null, "decimal", numberTypes, mapper.readTree("{\"progress\":12}"), "progress")), "Integer progress lost");
        check(new BigDecimal("12.5").equals(invoke(null, "decimal", numberTypes, mapper.readTree("{\"progress\":12.5}"), "progress")), "Decimal progress lost");
        fails(() -> invoke(null, "decimal", numberTypes, mapper.readTree("{\"progress\":false}"), "progress"), "Invalid progress accepted");
        Class<?>[] matchTypes = {JsonNode.class, Long.class, String.class};
        JsonNode old = mapper.readTree("{\"fileId\":1,\"filePath\":\"same.rvt\"}");
        check(Boolean.FALSE.equals(invoke(null, "isReplyFor", matchTypes, old, 2L, "same.rvt")), "Late result matched a different fileId");
        fails(() -> invoke(null, "isReplyFor", matchTypes, old, 1L, "other.rvt"), "Wrong filePath accepted");
        fails(() -> invoke(null, "isReplyFor", matchTypes, mapper.readTree("{\"filePath\":\"same.rvt\"}"), 1L, "same.rvt"), "Missing fileId accepted");
    }

    private static void connectionChecks() throws Exception {
        RevitWsClient client = new RevitWsClient("127.0.0.1", "1", "/not-started", 1000);
        AtomicInteger sends = new AtomicInteger();
        AtomicBoolean injectFailure = new AtomicBoolean();
        RemoteEndpoint.Basic remote = (RemoteEndpoint.Basic) Proxy.newProxyInstance(BimConversionCheck.class.getClassLoader(),
                new Class<?>[]{RemoteEndpoint.Basic.class}, (proxy, method, args) -> {
                    if ("sendText".equals(method.getName())) {
                        sends.incrementAndGet();
                        if (injectFailure.get()) throw new IOException("injected transport error");
                    }
                    return null;
                });
        Session session = (Session) Proxy.newProxyInstance(BimConversionCheck.class.getClassLoader(), new Class<?>[]{Session.class},
                (proxy, method, args) -> "isOpen".equals(method.getName()) ? true : "getBasicRemote".equals(method.getName()) ? remote : null);
        field(client, "running", true);
        client.onOpen(session);
        client.onMessage("pong", session);
        check(client.readMsg(1, TimeUnit.MILLISECONDS) == null, "Heartbeat leaked to task messages");
        client.onMessage("{\"fileId\":1}", session);
        check(client.readMsg(1, TimeUnit.MILLISECONDS) != null, "Response was lost");
        check(client.sendMsg("first") == client.getConnectionVersion(), "Send returned a stale connection version");
        long before = client.getConnectionVersion();
        injectFailure.set(true);
        fails(() -> client.sendMsg("second"), "Transport failure was swallowed");
        check(!client.isConnected() && client.getConnectionVersion() != before, "Transport failure did not invalidate connection");
        injectFailure.set(false);
        client.onOpen(session);
        check(sends.get() == 2, "Reconnect replayed a conversion request");
        client.stop();
        check(!client.isConnected(), "Stopped client remains connected");
        RevitWsClient shortIdle = new RevitWsClient("127.0.0.1", "1", "/not-started", 28800);
        Field heartbeat = RevitWsClient.class.getDeclaredField("heartbeatIntervalMillis");
        Field maintenance = RevitWsClient.class.getDeclaredField("maintenanceIntervalMillis");
        heartbeat.setAccessible(true);
        maintenance.setAccessible(true);
        check((Long) heartbeat.get(shortIdle) + (Long) maintenance.get(shortIdle) < 28800, "Heartbeat can exceed configured idle timeout");
        shortIdle.stop();
    }

    public static void main(String[] args) throws Exception {
        Path parent = Paths.get(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "bim-check-");
        Path uploads = Files.createDirectories(root.resolve("public-assets"));
        AstralConfig config = new AstralConfig();
        config.setUploadDir(uploads.toString());
        config.setUploadType("local");
        protocolChecks();
        connectionChecks();

        try (Fixture f = new Fixture(root, uploads)) {
            Result<?> upload = f.controller.doRvtUpload(new MemoryFile());
            check(upload.getCode() == 200, "Source upload failed");
            String relative = (String) upload.getResult();
            Path source = root.resolve(relative);
            check(relative.startsWith("bim-sources/") && relative.endsWith("/model_.rvt"), "Unsafe or public source path");
            check(Arrays.equals(SOURCE, Files.readAllBytes(source)), "Source upload changed bytes");
            check(!source.startsWith(uploads), "Source RVT placed in public assets");
            Class<?>[] sourceTypes = {String.class};
            fails(() -> invoke(f.controller, "resolveSource", sourceTypes, "../outside.rvt"), "Source traversal accepted");
            Class<?>[] productTypes = {Path.class, String.class};
            fails(() -> invoke(null, "resolveProduct", productTypes, source, "../outside.glb"), "Product traversal accepted");
            fails(() -> invoke(f.controller, "awaitMessage", new Class<?>[]{long.class, long.class}, System.nanoTime() - 1, 1L), "Expired conversion wait did not fail");

            Astral3DBimToGltf first = f.submit(relative);
            JSONObject firstRequest = f.revit.request();
            Astral3DBimToGltf second = f.submit(relative);
            check(f.revit.sent.poll(150, TimeUnit.MILLISECONDS) == null, "Multiple conversions ran concurrently");
            check("Origin".equals(first.getOptions().get("coordinateReference")) && Boolean.TRUE.equals(first.getOptions().get("useDraco")), "Effective options were not persisted");
            f.revit.reply(firstRequest, "progress", "progress", 12);
            f.complete(firstRequest);
            f.socket.await(first.getId(), "completed");
            JSONObject secondRequest = f.revit.request();
            f.revit.reply(firstRequest, "completed", "gltfPath", "does-not-exist.glb", "runSeconds", 1);
            f.revit.reply(secondRequest, "progress", "progress", 12.5);
            f.complete(secondRequest);
            f.socket.await(second.getId(), "completed");
            check(Arrays.equals(PRODUCT, Files.readAllBytes(uploads.resolve(second.getGltfFilePath()))), "Archive content/path mismatch");
            check(Arrays.equals(SOURCE, Files.readAllBytes(source)), "Success deleted or changed source");
            f.socket.terminalOnce(first.getId());
            f.socket.terminalOnce(second.getId());

            Path unrelated = Files.createDirectories(uploads.resolve("upload/bim/bim2gltf/unrelated")).resolve("keep.glb");
            Files.write(unrelated, PRODUCT);
            long beforeArchives;
            try (java.util.stream.Stream<Path> files = Files.walk(uploads)) { beforeArchives = files.filter(Files::isRegularFile).count(); }
            f.rejectSuccess.set(true);
            Astral3DBimToGltf rejected = f.submit(relative);
            f.complete(f.revit.request());
            f.socket.await(rejected.getId(), "failed");
            long afterArchives;
            try (java.util.stream.Stream<Path> files = Files.walk(uploads)) { afterArchives = files.filter(Files::isRegularFile).count(); }
            check(beforeArchives == afterArchives, "DB failure left an archived file");
            check(Files.isRegularFile(unrelated) && Files.isRegularFile(source) && Files.isRegularFile(source.getParent().resolve("product.glb")), "Rollback deleted unrelated/source/recovery product");
            f.socket.terminalOnce(rejected.getId());

            Astral3DBimToGltf missing = f.submit(relative);
            f.revit.reply(f.revit.request(), "completed", "gltfPath", "missing.glb", "runSeconds", 1);
            f.socket.await(missing.getId(), "failed");
            check(missing.getGltfFilePath().isEmpty(), "Missing product reported success");

            Astral3DBimToGltf failed = f.submit(relative);
            f.revit.reply(f.revit.request(), "failed", "message", "injected exporter failure");
            check("injected exporter failure".equals(f.socket.await(failed.getId(), "failed").getString("message")), "Exporter reason was lost");

            Astral3DBimToGltf disconnected = f.submit(relative);
            f.revit.request();
            f.revit.version++;
            f.socket.await(disconnected.getId(), "failed");
            f.socket.terminalOnce(disconnected.getId());

            f.revit.failSend = true;
            Astral3DBimToGltf sendFailed = f.submit(relative);
            f.socket.await(sendFailed.getId(), "failed");
            f.revit.failSend = false;
            check(f.revit.sent.isEmpty(), "Send failure was queued for replay");
            check(Arrays.equals(SOURCE, Files.readAllBytes(source)), "Failure changed source RVT");

            Astral3DBimToGltf active = f.submit(relative);
            f.revit.request();
            Astral3DBimToGltf queued = f.submit(relative);
            f.controller.stop();
            f.socket.await(active.getId(), "failed");
            f.socket.await(queued.getId(), "failed");
            f.socket.terminalOnce(active.getId());
            f.socket.terminalOnce(queued.getId());
        }
        System.out.println("PASS: " + checks + " BIM protocol, queue, lifecycle, archive and source-retention assertions; scratch=" + root);
    }
}
