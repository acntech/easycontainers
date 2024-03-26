package no.acntech.easycontainers.util.lang

import no.acntech.easycontainers.util.collections.prettyPrint
import no.acntech.easycontainers.util.text.NEW_LINE
import java.beans.Introspector
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
