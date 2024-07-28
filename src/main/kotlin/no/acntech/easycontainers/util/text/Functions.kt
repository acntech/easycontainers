package no.acntech.easycontainers.util.text

val WHITESPACE_REGEX = "\\s+".toRegex()

/**
 * Splits a string into a list of substrings using whitespace as the delimiter.
 *
 * @receiver The string to be split.
 * @return The list of substrings obtained by splitting the receiver string on whitespaces.
 */
fun String.splitOnWhites() = split(WHITESPACE_REGEX)

/**
 * Truncates the string if its length exceeds the specified maximum length. An affix can be appended
 * to the truncated string. By default, the truncation is performed from the end of the string with the
 * affix appended as a suffix.
 *
 * @param length The maximum length of the string.
 * @param affix The prefix or suffix to be added to the truncated string. Default value the empty string.
 * @param fromStart Boolean indicating whether the truncation should be performed from the start of the string or from
 *                 the end. Default value is false.
 * @return The truncated string with the specified affix appended.
 */
fun String.truncate(length: Int, affix: String = EMPTY_STRING, fromStart: Boolean = false): String {
   if (this.length <= length) return this
   val suffixLength = affix.length

   return if (!fromStart) {
      // Truncate from end
      if (length <= suffixLength) {
         affix.take(length)
      } else {
         this.take(length - suffixLength) + affix
      }

   } else {
      // Truncate from start
      if (length <= suffixLength) {
         affix.take(length)
      } else {
         affix + this.takeLast(length - suffixLength)
      }
   }
}