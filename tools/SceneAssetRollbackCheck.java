import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Exercises the real update/rollback path in a fresh scratch directory, never a service upload root. */
public class SceneAssetRollbackCheck {
    public static void main(String[] args) throws Exception {
        Path parent = Paths.get(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "scene-rollback-");
        Class<?> config = Class.forName("com.astral.common.config.AstralConfig");
        Object configObject = config.newInstance();
        config.getMethod("setUploadDir", String.class).invoke(configObject, root.toString());
        config.getMethod("setUploadType", String.class).invoke(configObject, "local");
        Class<?> entity = Class.forName("com.astral.business.scenes.entity.Astral3DScenes");
        Class<?> controller = Class.forName("com.astral.business.scenes.controller.Astral3DScenesController");
        Class<?> service = Class.forName("com.astral.business.scenes.service.Astral3DScenesService");
        Object old = entity.newInstance();
        entity.getMethod("setId", String.class).invoke(old, "test");
        entity.getMethod("setZip", String.class).invoke(old, "upload/old/scene.zip");
        entity.getMethod("setCoverPicture", String.class).invoke(old, "upload/old/cover.png");
        byte[] bytes = "must survive a rejected update".getBytes(StandardCharsets.UTF_8);
        String[] names = {"upload/old/scene.zip", "upload/old/cover.png", "upload/another/scene.zip", "upload/shared/cover.png"};
        for (String name : names) { Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.write(p,bytes); }
        Object target = controller.newInstance();
        Object proxy = Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service}, (p,m,a) -> {
            if (m.getName().equals("getById")) return old;
            throw new AssertionError("Rejected asset must not modify database: " + m.getName());
        });
        Field field=controller.getDeclaredField("astral3DScenesService");field.setAccessible(true);field.set(target,proxy);
        Object update=entity.newInstance();
        entity.getMethod("setZip",String.class).invoke(update,"upload/renderer-version-intentional-missing.zip");
        entity.getMethod("setCoverPicture",String.class).invoke(update,"upload/shared/cover.png");
        Object result=controller.getMethod("put",String.class,entity).invoke(target,"test",update);
        if (((Number)result.getClass().getMethod("getCode").invoke(result)).intValue()==200) throw new AssertionError("Expected rejected update");
        for (String name:names) {
            Path p=root.resolve(name);
            if (!Files.exists(p) || !java.util.Arrays.equals(bytes,Files.readAllBytes(p))) throw new AssertionError("Deleted unrelated/existing asset: "+name);
        }
        System.out.println("PASS: failed ZIP update preserves old, shared and unrelated assets; scratch="+root);
    }
}
