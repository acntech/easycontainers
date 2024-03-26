package no.acntech.easycontainers.util.collections

import no.acntech.easycontainers.util.text.EMPTY_STRING
import no.acntech.easycontainers.util.text.NEW_LINE
import java.util.*

private const val MASKED_PROP_VAL = "*****"

private val defaultKeyPatterns = listOf(
   ".*key.*",
   ".*password$",
   ".*passwd$",
   ".*pw$",
   ".*secret.*",
   ".*token.*",
   ".*credential.*",
   ".*auth.*",
   ".*access.*",
   ".*private.*"
)

fun Map<*, *>.prettyPrint(
   sortKeys: Boolean = true,
   offset: Int = 4,
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
         is Map<*, *> -> "\n${value.prettyPrint(sortKeys, offset * 2, paddingChar, keyValueSeparator)}"
         else -> value?.toString() ?: "null"
      }
      "${padding}${entry.key?.toString()}$keyPadding$keyValueSeparator$valueString"
   }
}

fun Map<String, String>.toCensoredCopy(
   keyPatterns: List<String> = defaultKeyPatterns,
   mask: String = MASKED_PROP_VAL,
): Map<String, String> {
   return entries.associateBy(
      { it.key },
      { entry ->
         var value = entry.value
         for (pattern in keyPatterns) {
            if (entry.key.lowercase().matches(Regex(pattern))) {
               value = mask
               break
            }
         }
         value
      }
   )
}

fun Properties.toCensoredMap(
   keyPatterns: List<String> = defaultKeyPatterns,
   mask: String = MASKED_PROP_VAL,
): Map<String, String> {
   return entries.associateBy(
      { it.key.toString() },
      { it.value.toString() }
   ).toCensoredCopy(keyPatterns, mask)
}
