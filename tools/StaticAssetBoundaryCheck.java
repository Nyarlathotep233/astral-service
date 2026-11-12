import com.astral.common.config.AstralConfig;
import com.astral.common.controller.CommonController;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Calls the real static-file handler against isolated sentinels; no HTTP server or business files. */
public class StaticAssetBoundaryCheck {
    private static final class Response {
        int status = 200;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ServletOutputStream output = new ServletOutputStream() {
            public void write(int value) { bytes.write(value); }
            public boolean isReady() { return true; }
            public void setWriteListener(WriteListener listener) { throw new UnsupportedOperationException(); }
        };
        HttpServletResponse proxy() {
            return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> {
                        if ("setStatus".equals(method.getName())) status = (Integer) args[0];
                        if ("getOutputStream".equals(method.getName())) return output;
                        return null;
                    });
        }
    }

    private static Response request(CommonController controller, String extractedPath) {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(StaticAssetBoundaryCheck.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())) {
                        if (HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE.equals(args[0])) return "/common/static/" + extractedPath;
                        if (HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE.equals(args[0])) return "/common/static/**";
                    }
                    return null;
                });
        Response response = new Response();
        controller.getFile(request, response.proxy());
        return response;
    }

    public static void main(String[] args) throws Exception {
        Path parent = Paths.get(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "static-boundary-");
        Path publicRoot = Files.createDirectory(root.resolve("public"));
        Path privateRoot = Files.createDirectory(root.resolve("bim-sources"));
        byte[] publicBytes = "public-sentinel".getBytes(StandardCharsets.UTF_8);
        byte[] privateBytes = "private-rvt-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(publicRoot.resolve("模型+正常.txt"), publicBytes);
        Files.write(privateRoot.resolve("sentinel.rvt"), privateBytes);
        AstralConfig config = new AstralConfig();
        config.setUploadDir(publicRoot.toString());
        config.setUploadType("local");
        CommonController controller = new CommonController();
        for (String name : new String[]{"模型+正常.txt", "%E6%A8%A1%E5%9E%8B%2B%E6%AD%A3%E5%B8%B8.txt"}) {
            Response result = request(controller, name);
            if (result.status != 200 || !Arrays.equals(publicBytes, result.bytes.toByteArray()))
                throw new AssertionError("Legitimate Unicode/plus asset changed: " + name);
        }
        // HandlerMapping can already have decoded one layer of a double-encoded URL.
        for (String escaped : new String[]{"%2e%2e/bim-sources/sentinel.rvt", "%2E%2E%5Cbim-sources%5Csentinel.rvt"}) {
            Response result = request(controller, escaped);
            if (result.status != 404 || result.bytes.size() != 0)
                throw new AssertionError("Encoded traversal exposed the private sentinel");
        }
        if (!Arrays.equals(privateBytes, Files.readAllBytes(privateRoot.resolve("sentinel.rvt"))))
            throw new AssertionError("Private source sentinel changed");
        System.out.println("PASS: 5 static-file boundary assertions (Unicode, plus, encoded slash/backslash traversal, source retention); scratch=" + root);
    }
}
