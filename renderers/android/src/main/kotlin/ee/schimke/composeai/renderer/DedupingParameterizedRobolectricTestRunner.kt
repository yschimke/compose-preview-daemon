package ee.schimke.composeai.renderer

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.MessageFormat
import java.util.Locale
import org.junit.Assert
import org.junit.runner.Runner
import org.junit.runners.Suite
import org.junit.runners.model.FrameworkField
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.TestClass
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.SandboxTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * [ParameterizedRobolectricTestRunner], with every per-parameter runner built on
 * [LocalVariableDedupingInjector] — so the sandbox defines classes through
 * [LocalVariableDedupingInstrumentor], as the daemon's `SandboxHoldingRunner` does
 * (compose-ai-tools#6230).
 *
 * Without it the Gradle batch render fails where the daemon renders: a Kotlin default-arguments
 * constructor that inlines `.dp` twice before delegating comes out of Robolectric's stock
 * instrumentation with two identical `$i$f$getDp` rows and HotSpot refuses to define the class
 * (`ClassFormatError: Duplicated LocalVariableTable attribute entry`). The stock runner is `final`
 * and its per-parameter runner `private`, and both take Robolectric's default injector, so there is
 * no seam to bind the instrumentor through; this is a copy of its parameter handling on top of a
 * runner that takes an injector.
 *
 * Test classes keep using [ParameterizedRobolectricTestRunner.Parameters] and
 * [ParameterizedRobolectricTestRunner.Parameter]: only the `@RunWith` changes.
 */
public class DedupingParameterizedRobolectricTestRunner(klass: Class<*>) :
  Suite(klass, emptyList()) {

  private val runners: List<Runner>

  init {
    val classLoader = javaClass.classLoader
    val method = parametersMethod(testClass, classLoader)
    val pattern =
      method.getAnnotation(ParameterizedRobolectricTestRunner.Parameters::class.java).name
    runners =
      parametersList(testClass, classLoader).mapIndexed { index, params ->
        ForParameters(testClass.javaClass, index, nameFor(pattern, index, asArray(params)))
      }
  }

  override fun getChildren(): List<Runner> = runners

  private class ForParameters(type: Class<*>, private val index: Int, private val name: String) :
    RobolectricTestRunner(type, LocalVariableDedupingInjector.INJECTOR) {

    private fun createTestInstance(bootstrapped: Class<*>): Any {
      val constructors = bootstrapped.constructors
      Assert.assertEquals(1, constructors.size)
      return if (!fieldsAreAnnotated()) {
        constructors[0].newInstance(*computeParams(bootstrapped.classLoader))
      } else {
        constructors[0].newInstance().also { injectFields(it, bootstrapped.classLoader) }
      }
    }

    private fun computeParams(classLoader: ClassLoader): Array<Any?> {
      val list = parametersList(testClass, classLoader)
      if (index >= list.size) {
        throw Exception(
          "Re-computing the parameter list returned a different number of parameters values. " +
            "Is the data() method of your test non-deterministic?"
        )
      }
      return asArray(list[index])
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectFields(instance: Any, classLoader: ClassLoader) {
      val parameterClass =
        classInLoader(ParameterizedRobolectricTestRunner.Parameter::class.java, classLoader)
          as Class<Annotation>
      val parameters = computeParams(classLoader)
      val found = HashSet<Int>()
      for (field in instance.javaClass.fields) {
        val annotation = field.getAnnotation(parameterClass) ?: continue
        val i = ReflectionHelpers.callInstanceMethod<Int>(annotation, "value")
        found += i
        try {
          field.set(instance, parameters[i])
        } catch (e: IllegalArgumentException) {
          throw Exception(
            "${testClass.name}: Trying to set ${field.name} with the value ${parameters[i]} that " +
              "is not the right type (${parameters[i]?.javaClass?.simpleName} instead of " +
              "${field.type.simpleName}).",
            e,
          )
        }
      }
      check(found.size == parameters.size) {
        String.format(
          Locale.US,
          "Provided %d parameters, but only found fields for parameters: %s",
          parameters.size,
          found,
        )
      }
    }

    override fun getName(): String = name

    override fun testName(method: FrameworkMethod): String = method.name + name

    override fun validateConstructor(errors: MutableList<Throwable>) {
      validateOnlyOneConstructor(errors)
      if (fieldsAreAnnotated()) validateZeroArgConstructor(errors)
    }

    override fun toString(): String = "DedupingParameterizedRobolectricTestRunner $name"

    override fun validateFields(errors: MutableList<Throwable>) {
      super.validateFields(errors)
      if (!fieldsAreAnnotated()) return
      val fields = annotatedFields()
      val used = IntArray(fields.size)
      for (each in fields) {
        val i =
          each.field.getAnnotation(ParameterizedRobolectricTestRunner.Parameter::class.java).value
        if (i < 0 || i > fields.size - 1) {
          errors +=
            Exception(
              "Invalid @Parameter value: $i. @Parameter fields counted: ${fields.size}. " +
                "Please use an index between 0 and ${fields.size - 1}."
            )
        } else {
          used[i]++
        }
      }
      used.forEachIndexed { i, uses ->
        when {
          uses == 0 -> errors += Exception("@Parameter($i) is never used.")
          uses > 1 -> errors += Exception("@Parameter($i) is used more than once ($uses).")
        }
      }
    }

    override fun getHelperTestRunner(bootstrapped: Class<*>): SandboxTestRunner.HelperTestRunner {
      val outer = this
      return object : RobolectricTestRunner.HelperTestRunner(bootstrapped) {
        override fun validateConstructor(errors: MutableList<Throwable>) {
          outer.validateOnlyOneConstructorFor(errors)
        }

        override fun createTest(): Any = outer.createTestInstance(testClass.javaClass)

        override fun testName(method: FrameworkMethod): String = outer.testName(method)

        override fun toString(): String = "HelperTestRunner for $outer"
      }
    }

    fun validateOnlyOneConstructorFor(errors: MutableList<Throwable>) =
      validateOnlyOneConstructor(errors)

    private fun annotatedFields(): List<FrameworkField> =
      testClass.getAnnotatedFields(ParameterizedRobolectricTestRunner.Parameter::class.java)

    private fun fieldsAreAnnotated(): Boolean = annotatedFields().isNotEmpty()
  }

  private companion object {
    fun asArray(params: Any?): Array<Any?> =
      @Suppress("UNCHECKED_CAST") if (params is Array<*>) params as Array<Any?> else arrayOf(params)

    @Suppress("UNCHECKED_CAST")
    fun parametersList(testClass: TestClass, classLoader: ClassLoader): List<Any?> {
      val parameters = parametersMethod(testClass, classLoader).invokeExplosively(null)
      return if (parameters != null && parameters.javaClass.isArray) {
        (parameters as Array<Any?>).asList()
      } else {
        parameters as List<Any?>
      }
    }

    fun parametersMethod(testClass: TestClass, classLoader: ClassLoader): FrameworkMethod {
      for (each in
        testClass.getAnnotatedMethods(ParameterizedRobolectricTestRunner.Parameters::class.java)) {
        val modifiers = each.method.modifiers
        if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)) {
          val inLoader = methodInLoader(each.method, classLoader)
          return if (inLoader == each.method) each else FrameworkMethod(inLoader)
        }
      }
      throw Exception("No public static parameters method on class ${testClass.name}")
    }

    fun nameFor(pattern: String, index: Int, parameters: Array<Any?>): String =
      "[" + MessageFormat.format(pattern.replace("{index}", index.toString()), *parameters) + "]"

    fun methodInLoader(method: Method, classLoader: ClassLoader): Method {
      val declaring = method.declaringClass
      if (declaring.classLoader == classLoader) return method
      return classInLoader(declaring, classLoader).getMethod(method.name, *method.parameterTypes)
    }

    fun classInLoader(klass: Class<*>, classLoader: ClassLoader): Class<*> =
      if (klass.classLoader == classLoader) klass else classLoader.loadClass(klass.name)
  }
}
