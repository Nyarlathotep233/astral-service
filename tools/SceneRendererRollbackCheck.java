import java.lang.reflect.*;
import java.util.Map;

/** Standalone regression check; loads the built backend through Spring Boot's PropertiesLauncher. */
public class SceneRendererRollbackCheck {
    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    public static void main(String[] args) throws Exception {
        Class<?> controllerType = Class.forName("com.astral.business.scenes.controller.Astral3DScenesController");
        Class<?> entityType = Class.forName("com.astral.business.scenes.entity.Astral3DScenes");
        Class<?> serviceType = Class.forName("com.astral.business.scenes.service.Astral3DScenesService");
        Class<?> contextType = Class.forName(controllerType.getName() + "$SceneUpdateRollbackContext");
        Constructor<?> constructor = contextType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method rollback = controllerType.getDeclaredMethod("rollbackUpdate", String.class, entityType, contextType);
        rollback.setAccessible(true);
        for (String marker : new String[]{null, "webgl", "webgpu"}) {
            Object controller = controllerType.newInstance();
            Object entity = entityType.newInstance();
            entityType.getMethod("setRendererVersion", String.class).invoke(entity, marker);
            final boolean[] checked = {false};
            Object service = Proxy.newProxyInstance(serviceType.getClassLoader(), new Class<?>[]{serviceType}, (proxy, method, values) -> {
                if (!method.getName().equals("update")) throw new AssertionError("Unexpected call: " + method.getName());
                if (values[0] != entity) throw new AssertionError("Original entity not restored");
                Object wrapper = values[1];
                String set = (String) wrapper.getClass().getMethod("getSqlSet").invoke(wrapper);
                Map<?, ?> parameters = (Map<?, ?>) wrapper.getClass().getMethod("getParamNameValuePairs").invoke(wrapper);
                if (marker == null && (set == null || !set.contains("rendererVersion") || !parameters.containsValue(null))) {
                    throw new AssertionError("Historical null marker must be explicitly restored");
                }
                if (marker != null && !marker.equals(entityType.getMethod("getRendererVersion").invoke(values[0]))) {
                    throw new AssertionError("Existing marker changed during rollback");
                }
                checked[0] = true;
                return true;
            });
            field(controller, "astral3DScenesService", service);
            Object context = constructor.newInstance();
            field(context, "updated", true);
            field(context, "localUpload", false);
            rollback.invoke(controller, "unit-test-only", entity, context);
            if (!checked[0]) throw new AssertionError("Rollback did not update the database");
        }
        System.out.println("PASS: rendererVersion rollback for null / webgl / webgpu");
    }
}
