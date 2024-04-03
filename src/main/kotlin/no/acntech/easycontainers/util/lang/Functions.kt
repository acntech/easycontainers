package no.acntech.easycontainers.util.lang

import com.google.common.base.CaseFormat
import no.acntech.easycontainers.util.collections.prettyPrint
import no.acntech.easycontainers.util.text.NEW_LINE
import java.beans.Introspector
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Converts an object to a Map representation, including its properties or Java bean properties.
 *
 * @param defaultOverrides a map of default properties to override the retrieved properties
 * @return a map representation of the object, with property names as keys and property values as values
 */
fun Any.toMap(defaultOverrides: Map<String, Any> = emptyMap()): Map<String, Any?> {
   val props = mutableMapOf<String, Any?>()

   // Check if it's a Kotlin class
   if (this::class.findAnnotation<Metadata>() != null) {
      // Handle Kotlin properties
      this::class.memberProperties
         .filter { it.getter.isAccessible }
         .forEach { prop ->
            try {
               props[prop.name] = prop.call(this)
            } catch (e: Exception) {
               props[prop.name] = "Error calling getter (Kotlin): ${e.message}"
            }
         }
   } else { // Otherwise, handle it as a Java bean
      val beanInfo = Introspector.getBeanInfo(this.javaClass)
      for (propertyDescriptor in beanInfo.propertyDescriptors) {
         val name = propertyDescriptor.name
         val getter = propertyDescriptor.readMethod
         if (getter != null && !props.containsKey(name)) {
            try {
               props[name] = getter.invoke(this)
            } catch (e: Exception) {
               props[name] = "Error calling getter: ${e.message}"
            }
         }
      }
   }

   // Merge with defaultOverrides
   props.putAll(defaultOverrides)

   return props
}

/**
 * Returns a string representation of the calling object in a pretty format.
 *
 * @param fallbackMap the fallback map to use if conversion to map fails (default is an empty map)
 * @return the string representation of the object
 */
fun Any.prettyPrintMe(fallbackMap: Map<String, Any> = emptyMap()): String {
   val defaultToString = "${javaClass.name}@${Integer.toHexString(System.identityHashCode(this))}$NEW_LINE"
   val map: Map<String, Any?> = this.toMap(fallbackMap)
   return defaultToString + map.prettyPrint()
}

val SNAKE_TO_CAMEL: TransformFunction<String, String> =
   createCaseFormatTransformFunction(CaseFormat.LOWER_UNDERSCORE, CaseFormat.LOWER_CAMEL)

typealias TransformFunction<T, R> = (T) -> R

fun <T> identityTransform(): TransformFunction<T, T> = { it } // it refers to the input itself

fun createCaseFormatTransformFunction(fromFormat: CaseFormat, toFormat: CaseFormat): TransformFunction<String, String> {
   return { input: String ->
      fromFormat.to(toFormat, input)
   }
}

fun defaultOnError(e: Exception) {}
fun defaultNoMatchHandler(e: Exception): Nothing = throw e
fun defaultFinally() {}

val DEFAULT_EXCEPTION_LIST: List<KClass<out Exception>> = listOf(Exception::class)

/**
 * Guards a given block of code, and calls the onError function if any of the specified exceptions (or their subclasses) are thrown.
 * If the thrown exception is not one of the specified exceptions (or a subclass), the noMatchHandler function is called. The
 * finallyBlock is always executed, defaulting to an empty function.
 * *
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
   errors: List<KClass<out Exception>> = DEFAULT_EXCEPTION_LIST,
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
      handlers.firstOrNull { (classes, _) -> classes.any { it.isInstance(e) } }
         ?.let { (_, handler) -> handler(e) }
         ?: noMatchHandler(e)
   } finally {
      finallyBlock.invoke()
   }
}

