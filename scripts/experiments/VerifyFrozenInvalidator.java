/* Run with each patched sandbox jar first on the classpath. */
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public final class VerifyFrozenInvalidator {
  public static void main(String[] args) throws Exception {
    Class<?> type = Class.forName("org.robolectric.internal.bytecode.ShadowInvalidator");
    Object invalidator = type.getConstructor().newInstance();
    var invalidate = type.getMethod("invalidateClasses", java.util.Collection.class);
    var freeze = type.getMethod("markBindingsFrozen");
    invalidate.invoke(invalidator, List.of("android.view.View"));
    freeze.invoke(invalidator);
    invalidate.invoke(invalidator, List.of());
    try {
      invalidate.invoke(invalidator, List.of("android.view.View"));
      throw new AssertionError("Changed shadows were silently accepted");
    } catch (InvocationTargetException expected) {
      if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
    }
    // The restriction belongs to one sandbox, not every sandbox in the JVM.
    invalidate.invoke(type.getConstructor().newInstance(), List.of("android.view.View"));
    System.out.println("Initial and empty invalidation allowed; post-binding changes rejected; instances isolated");
  }
}
