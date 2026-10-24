package ee.schimke.composeai.daemon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [CompileActivity], which the sandbox pool's background boot waits on. */
class CompileActivityTest {

  @Before
  @After
  fun reset() {
    CompileActivity.resetForTest()
  }

  @Test(timeout = 10_000)
  fun `nothing compiling and nothing compiled means no wait`() {
    assertFalse(CompileActivity.isActive)
    assertEquals(0L, CompileActivity.awaitQuiet(quietMs = 5_000, maxWaitMs = 5_000))
  }

  @Test(timeout = 10_000)
  fun `waits for a running compile and then the quiet period`() {
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)
    val compile = thread {
      CompileActivity.during {
        inside.countDown()
        release.await()
      }
    }
    assertTrue(inside.await(5, TimeUnit.SECONDS))
    assertTrue(CompileActivity.isActive)
    var waitedMs = -1L
    var returnedAt = 0L
    val waiter = thread {
      waitedMs = CompileActivity.awaitQuiet(quietMs = 300, maxWaitMs = 8_000)
      returnedAt = System.nanoTime()
    }
    Thread.sleep(400)
    assertTrue("must not return while the compile runs", waiter.isAlive)
    val endedAt = System.nanoTime()
    release.countDown()
    compile.join()
    waiter.join(5_000)
    assertFalse(waiter.isAlive)
    assertFalse(CompileActivity.isActive)
    assertTrue("waited $waitedMs ms", waitedMs >= 400)
    val afterEndMs = (returnedAt - endedAt) / 1_000_000
    assertTrue("returned $afterEndMs ms after the compile ended", afterEndMs >= 250)
  }

  @Test(timeout = 10_000)
  fun `a recent compile holds the waiter for the rest of the quiet period`() {
    CompileActivity.during {}
    val waitedMs = CompileActivity.awaitQuiet(quietMs = 300, maxWaitMs = 5_000)
    assertTrue("waited $waitedMs ms", waitedMs in 200..2_000)
    assertEquals(0L, CompileActivity.awaitQuiet(quietMs = 300, maxWaitMs = 5_000))
  }

  @Test(timeout = 10_000)
  fun `a compile that never ends holds the waiter only until the cap`() {
    val release = CountDownLatch(1)
    val inside = CountDownLatch(1)
    val compile = thread {
      CompileActivity.during {
        inside.countDown()
        release.await()
      }
    }
    try {
      assertTrue(inside.await(5, TimeUnit.SECONDS))
      val waitedMs = CompileActivity.awaitQuiet(quietMs = 0, maxWaitMs = 300)
      assertTrue("waited $waitedMs ms", waitedMs in 250..3_000)
      assertEquals(0L, CompileActivity.awaitQuiet(quietMs = 0, maxWaitMs = 0))
    } finally {
      release.countDown()
      compile.join()
    }
  }

  @Test(timeout = 10_000)
  fun `a throwing compile still ends`() {
    runCatching { CompileActivity.during { error("boom") } }
    assertFalse(CompileActivity.isActive)
  }
}
