package ee.schimke.composeai.daemon;

public class MethodCacheFixture implements MethodCacheFixtureDefault {
  public String value() { return "fixture"; }
}

interface MethodCacheFixtureDefault {
  default String inheritedDefault() { return "default"; }
}
