package no.acntech.easycontainers.util.collections

import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.NEW_LINE

/**
 * Returns a string representation of the map in a pretty format.
 *
 * @param sortKeys determines whether the keys should be sorted in ascending order (default is true)
 * @param offset the number of spaces for indentation (default is 4)
 * @param paddingChar the character used for padding (default is ' ')
 * @param keyValueSeparator the separator between the key and value (default is ": ")
 * @return the string representation of the map
 */
fun Map<*, *>.prettyPrint(
   sortKeys: Boolean = true,
   offset: Int = 2,
   paddingChar: Char = ' ',
   keyValueSeparator: String = ": ",
): String {
   val effectiveMap = if (sortKeys) entries.sortedBy { it.key?.toString() ?: EMPTY_STRING } else entries

   if (effectiveMap.isEmpty()) {
      return EMPTY_STRING
   }

   val longestKeyLength = effectiveMap.maxOf { it.key?.toString()?.length ?: 0 }
   val padding = EMPTY_STRING.padStart(offset, paddingChar)

   return effectiveMap.joinToString(separator = NEW_LINE) { entry ->
      val keyPadding = EMPTY_STRING.padStart(longestKeyLength - (entry.key?.toString()?.length ?: 0), ' ')
      val valueString = when (val value = entry.value) {
         is Map<*, *> -> "$NEW_LINE${value.prettyPrint(sortKeys, offset + 2, paddingChar, keyValueSeparator)}"
         is List<*> -> "$NEW_LINE${value.prettyPrint(offset + 2, paddingChar)}"
         else -> value?.toString() ?: "null"
      }
      "${padding}${entry.key?.toString()}$keyPadding$keyValueSeparator$valueString"
   }
}

/**
 * Returns a string representation of the list in a pretty format.
 *
 * @param offset the number of spaces for indentation (default is 2)
 * @param paddingChar the character used for padding (default is ' ')
 * @return the string representation of the list
 */
fun List<*>.prettyPrint(
   offset: Int = 2,
   paddingChar: Char = ' ',
): String {
   val padding = EMPTY_STRING.padStart(offset, paddingChar)

   return this.joinToString(separator = NEW_LINE) { item ->
      when (item) {
         is Map<*, *> -> "$padding${item.prettyPrint(offset = offset + 2, paddingChar = paddingChar)}"
         is List<*> -> "$padding${item.prettyPrint(offset = offset + 2, paddingChar = paddingChar)}"
         else -> "$padding$item"
      }
   }
}

/**
 * Converts each key-value pair in the map to a string representation,
 * where the key is converted to a String using its toString() method,
 * and the value is converted to a String using its toString() method.
 * If the key or value is null, it is converted to the string "null".
 *
 * @return a new map where each key-value pair is converted to a string representation.
 * @see Any.toString
 * @see Map.mapValues
 * @see Map.mapKeys
 */
fun Map<*, *>.toStringMap(): Map<String, String> {
   return this.mapValues {
      it.value?.toString() ?: "null"
   }
      .mapKeys { it.key?.toString() ?: "null" }
}

/**
 * Converts each element in the list to a string representation.
 * If the element is null, it is converted to the string "null".
 *
 * @return a new list where each element is converted to its string representation.
 * @see Any.toString
 * @see List.map
 */
fun List<*>.toStringList(): List<String> {
   return this.map { it?.toString() ?: "null" }
}
