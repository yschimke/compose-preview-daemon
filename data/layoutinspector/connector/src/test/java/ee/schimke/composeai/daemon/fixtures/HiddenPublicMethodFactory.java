package ee.schimke.composeai.daemon.fixtures;

public final class HiddenPublicMethodFactory {
  public static Object receiver() { return new HiddenGetter(); }

  private static final class HiddenGetter {
    public String value() { return "hidden class"; }
  }
}
