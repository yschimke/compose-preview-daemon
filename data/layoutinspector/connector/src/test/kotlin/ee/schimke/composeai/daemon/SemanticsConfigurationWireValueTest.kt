package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticsConfigurationWireValueTest {
  @Test
  fun `wire value is independent of semantics property insertion order`() {
    val roleFirst =
      SemanticsConfiguration().apply {
        this[SemanticsProperties.Role] = Role.Image
        this[SemanticsProperties.ContentDescription] = listOf("Mail")
      }
    val descriptionFirst =
      SemanticsConfiguration().apply {
        this[SemanticsProperties.ContentDescription] = listOf("Mail")
        this[SemanticsProperties.Role] = Role.Image
      }

    assertEquals(
      ComposeLayoutInspector.canonicalWireValue(roleFirst),
      ComposeLayoutInspector.canonicalWireValue(descriptionFirst),
    )
    assertEquals(
      "{ContentDescription=[Mail], Role=Image}",
      ComposeLayoutInspector.canonicalWireValue(roleFirst),
    )
  }

  @Test
  fun `wire value preserves authored strings that resemble runtime identities`() {
    val custom = SemanticsPropertyKey<String>("Custom")
    val configuration = SemanticsConfiguration().apply { this[custom] = "account.name@abcdef.com" }

    assertEquals(
      "{Custom=account.name@abcdef.com}",
      ComposeLayoutInspector.canonicalWireValue(configuration),
    )
  }

  @Test
  fun `wire value canonicalizes short runtime identities from non-string values`() {
    val custom = SemanticsPropertyKey<Any>("Custom")
    val runtimeValue =
      object {
        override fun toString() = "example.Value@abcde"
      }
    val configuration = SemanticsConfiguration().apply { this[custom] = runtimeValue }

    assertEquals(
      "{Custom=example.Value@<identity>}",
      ComposeLayoutInspector.canonicalWireValue(configuration),
    )
  }

  @Test
  fun `wire value uses property value to break duplicate name ties`() {
    val firstKey = SemanticsPropertyKey<String>("Flag")
    val secondKey = SemanticsPropertyKey<String>("Flag")
    val configuration =
      SemanticsConfiguration().apply {
        this[firstKey] = "initial-a"
        this[secondKey] = "initial-b"
      }
    val firstValueInIterationOrder = configuration.first().value
    if (firstValueInIterationOrder == "initial-a") {
      configuration[firstKey] = "b"
      configuration[secondKey] = "a"
    } else {
      configuration[firstKey] = "a"
      configuration[secondKey] = "b"
    }

    assertEquals("{Flag=a, Flag=b}", ComposeLayoutInspector.canonicalWireValue(configuration))
  }

  @Test
  fun `runtime identity normalization preserves embedded and whole-key boundaries`() {
    val cases =
      listOf(
        "plain diagnostic value" to "plain diagnostic value",
        "example.Value@zxy" to "example.Value@zxy",
        "example.Value@ABCDEF0123456789" to "example.Value@<identity>",
        "[example.Value@f]" to "[example.Value@<identity>]",
        "example.Owner\$\$Lambda/0xAbCd@f" to "example.Owner\$\$Lambda/<address>@<identity>",
        "example.Owner\$\$Lambda/0xAbCd" to "example.Owner\$\$Lambda/<address>",
        "example.Owner\$\$Lambda/0xINVALID" to "example.Owner\$\$Lambda/0xINVALID",
      )
    for ((raw, expected) in cases) {
      val configuration =
        SemanticsConfiguration().apply {
          this[SemanticsPropertyKey<Any>("Custom")] =
            object {
              override fun toString() = raw
            }
        }
      assertEquals(
        raw,
        "{Custom=$expected}",
        ComposeLayoutInspector.canonicalWireValue(configuration),
      )
    }
    val configuration =
      SemanticsConfiguration().apply {
        this[SemanticsPropertyKey<String>("[example.Value@f]")] = "embedded key"
        this[SemanticsPropertyKey<String>("example.Value@f")] = "whole key"
        this[SemanticsPropertyKey<String>("example.Value@0123456789abcdef0")] = "long key"
      }
    assertEquals(
      "{[example.Value@f]=embedded key, example.Value@0123456789abcdef0=long key, example.Value@<identity>=whole key}",
      ComposeLayoutInspector.canonicalWireValue(configuration),
    )
  }
}
