package no.acntech.easycontainers.util.lang

import com.google.common.base.CaseFormat
import no.acntech.easycontainers.util.collections.prettyPrint
import no.acntech.easycontainers.util.collections.toStringList
import no.acntech.easycontainers.util.collections.toStringMap
import no.acntech.easycontainers.util.text.NEW_LINE
import java.beans.Introspector
import java.beans.PropertyDescriptor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

private const val MAX_DEPTH = 5
private const val ERROR_CIRCULAR_REF = "Circular reference detected"
private const val ERROR_MAX_DEPTH = "Max depth reached"
private const val ERROR_GETTER_INVOKATION = "Error calling getter: "

/**
 * Converts an object to a Map representation, including its properties or Java bean properties.
 *
 * @param defaultOverrides a map of default properties to override the retrieved properties
 * @return a map representation of the object, with property names as keys and property values as values
 */
fun Any.asStringMap(
   defaultOverrides: Map<String, Any?> = emptyMap(),
   visited: MutableSet<Any> = mutableSetOf(),
   depth: Int = 0,
   maxDepth: Int = MAX_DEPTH,
): Map<String, Any?> {
   if (depth > maxDepth) {
      return mapOf(ERROR_MAX_DEPTH to true)
   }

   val props = mutableMapOf<String, Any?>()

   if (visited.contains(this)) {
      return mapOf(ERROR_CIRCULAR_REF to true)
   }
   visited.add(this)

   props["instance"] = "${javaClass.name}@${Integer.toHexString(System.identityHashCode(this))}"

   processProperties(this, props, visited, defaultOverrides, depth, maxDepth)

   props.putAll(defaultOverrides)

   return props
}

private fun processProperties(
   anyObj: Any,
   props: MutableMap<String, Any?>,
   visited: MutableSet<Any>,
   defaultOverrides: Map<String, Any?>,
   depth: Int,
   maxDepth: Int,
) {

   fun handleProperty(property: PropertyDescriptor) {
      val method = property.readMethod
      if (method != null && Modifier.isPublic(method.modifiers)) {
         tryToInvokeGetter(anyObj, property.name, method, props, visited, defaultOverrides, depth, maxDepth)
      }
   }

   // Java reflection
   val beanInfo = Introspector.getBeanInfo(anyObj.javaClass)
   for (propertyDescriptor in beanInfo.propertyDescriptors) {
      handleProperty(propertyDescriptor)
   }

   // Kotlin reflection
   for (property in anyObj::class.memberProperties) {
      if (property.javaField != null && property.isAccessible) {
         if (!props.containsKey(property.name)) {
            val value = property.call(anyObj)
            handlePropertyValue(props, property.name, value, visited, defaultOverrides, depth, maxDepth)
         }
      }
   }
}

private fun tryToInvokeGetter(
   anyObj: Any,
   name: String,
   method: Method,
   props: MutableMap<String, Any?>,
   visited: MutableSet<Any>,
   defaultOverrides: Map<String, Any?>,
   depth: Int,
   maxDepth: Int,
) {
   try {
      val value = method.invoke(anyObj)
      handlePropertyValue(props, name, value, visited, defaultOverrides, depth, maxDepth)
   } catch (e: Exception) {
      props[name] = "$ERROR_GETTER_INVOKATION ${e.message}"
   }
}

private fun handlePropertyValue(
   props: MutableMap<String, Any?>,
   name: String,
   value: Any?,
   visited: MutableSet<Any>,
   defaultOverrides: Map<String, Any?>,
   depth: Int,
   maxDepth: Int,
) {
   props[name] = when {
      value == null -> null
      visited.contains(value) -> ERROR_CIRCULAR_REF
      value is Map<*, *> -> value.toStringMap()
      value is List<*> -> value.toStringList()
      shouldStringify(value) -> value.toString()
      else -> value.asStringMap(defaultOverrides, visited, depth + 1, maxDepth)
   }
}

private fun shouldStringify(value: Any): Boolean {
   return value.javaClass.`package`?.name?.startsWith("java") == true ||
      value.javaClass.`package`?.name?.startsWith("kotlin") == true ||
      value.javaClass.isPrimitive ||
      value is String ||
      value is Number ||
      value is Boolean
}


/**
 * Returns a string representation of the calling object in a pretty format.
 *
 * @param fallbackMap the fallback map to use if conversion to map fails (default is an empty map)
 * @return the string representation of the object
 */
fun Any.prettyPrintMe(fallbackMap: Map<String, Any> = emptyMap()): String {
   val defaultToString = "${this.javaClass.name}@${Integer.toHexString(System.identityHashCode(this))}$NEW_LINE"
   val map: Map<String, Any?> = this.asStringMap(fallbackMap)
   return defaultToString + map.prettyPrint()
}

typealias Transform<T, R> = (T) -> R

val SNAKE_TO_CAMEL: Transform<String, String> =
   createCaseFormatTransformFunction(CaseFormat.LOWER_UNDERSCORE, CaseFormat.LOWER_CAMEL)

fun <T> identityTransform(): Transform<T, T> = { it } // it refers to the input itself

fun createCaseFormatTransformFunction(fromFormat: CaseFormat, toFormat: CaseFormat): Transform<String, String> {
   return { input: String ->
      fromFormat.to(toFormat, input)
   }
}

fun defaultOnError(e: Exception) {}
fun defaultNoMatchHandler(e: Exception): Nothing = throw e
fun defaultFinally() {}

/**
 * Guards a given block of code, and calls the onError function if any of the specified exceptions (or their subclasses) are
 * thrown. If the thrown exception is not one of the specified exceptions (or a subclass), the noMatchHandler function is called.
 * The finallyBlock is always executed, defaulting to an empty function.
 * <p>
 * Example usage:
 * <pre><code>
 * guardedExecution(
 *    {
 *    // Code which might throw an exception
 *    throw IllegalStateException("An error has occurred!")
 *    },
 *    listOf(IllegalStateException::class) to { e: Exception -> println("Handle IllegalStateException: ${e.message}") },
 *    { e: Exception -> println("No match handler for: ${e.message}") },
 *    { println("Finally block executed") }
 * )
 * </code></pre>
 *
 * @param block The block of code to guard.
 * @param errors The exceptions to guard against. Defaults to Exception.
 * @param onError The function to call if any of the specified exceptions are thrown. Defaults to an empty function.
 * @param noMatchHandler The function to call if the thrown exception is not one of the specified exceptions. Defaults to
 *                       rethrowing the exception.
 * @param finallyBlock The block of code to execute after the guarded block, regardless of whether an exception was thrown or not.
 */
inline fun guardedExecution(
   block: () -> Unit,
   errors: List<KClass<out Exception>> = listOf(Exception::class),
   noinline onError: (Exception) -> Unit = { e -> defaultOnError(e) },
   noMatchHandler: (Exception) -> Unit = { e -> defaultNoMatchHandler(e) },
   finallyBlock: () -> Unit = { defaultFinally() },
) {
   guardedExecution(
      block = block,
      handlers = arrayOf(errors to onError),
      noMatchHandler = noMatchHandler,
      finallyBlock = finallyBlock
   )
}

/**
 * A more generic version of the guardedExecution function, which allows for specifying a list of pairs of exception classes and
 * their corresponding handler functions. If an exception is thrown, the first matching handler is called. If no handler matches,
 * the noMatchHandler is called. If noMatchHandler is not specified, the exception is rethrown. The finallyBlock is always executed,
 * defaulting to an empty function.
 *
 * Example usage:
 * <pre><code>
 * guardedExecution(
 *     {
 *         // Code which might throw an exception
 *         throw IllegalStateException("An error has occurred!")
 *     },
 *     arrayOf(
 *         listOf(IllegalStateException::class) to { e: Exception -> println("Handle IllegalStateException: ${e.message}") },
 *         listOf(RuntimeException::class) to { e: Exception -> println("Handle RuntimeException: ${e.message}") }
 *     ),
 *     { e: Exception -> println("No match handler for: ${e.message}") },
 *     { println("Finally block executed") }
 * )
 * </code></pre>
 *
 * @param block The block of code to guard.
 * @param handlers A list of pairs, where the first element is a list of exception classes, and the second element is the
 *                 handler function to be called if any of the exception classes matches.
 * @param noMatchHandler The function to call if no handler matches the error's class. Defaults to throwing the error.
 * @param finallyBlock The block of code to execute after the guarded block, regardless of whether an exception was thrown or not.
 */
inline fun guardedExecution(
   block: () -> Unit,
   vararg handlers: Pair<List<KClass<out Exception>>, (Exception) -> Unit>,
   noMatchHandler: (Exception) -> Unit = { e -> defaultNoMatchHandler(e) },
   finallyBlock: () -> Unit = { defaultFinally() },
) {
   try {
      block.invoke()
   } catch (e: Exception) {
      handlers.firstOrNull { (classes, _) -> classes.any { it.isInstance(e) } }?.let { (_, handler) -> handler(e) }
         ?: noMatchHandler(e)
   } finally {
      finallyBlock.invoke()
   }
}

