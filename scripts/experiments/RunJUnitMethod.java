/* Executes one existing test method so experimental checks have fresh JVM state. */
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;

public final class RunJUnitMethod {
  public static void main(String[] args) throws Exception {
    Result result = new JUnitCore().run(Request.method(Class.forName(args[0]), args[1]));
    result.getFailures().forEach(System.out::println);
    System.out.println("Tests=" + result.getRunCount() + " failures=" + result.getFailureCount());
    System.exit(result.wasSuccessful() && result.getRunCount() == 1 ? 0 : 1);
  }
}
