package no.acntech.easycontainers.util.text

val WHITESPACE_REGEX = "\\s+".toRegex()

fun String.splitOnWhites() = split(WHITESPACE_REGEX)

fun String.chop(length: Int, suffix: String = "...", chopFromStart: Boolean = false): String {
   if (this.length <= length) return this
   val suffixLength = suffix.length

   return if (!chopFromStart) {
      // Chop from end
      if (length <= suffixLength) suffix.take(length)
      else this.take(length - suffixLength) + suffix
   } else {
      // Chop from start
      if (length <= suffixLength) suffix.take(length)
      else suffix + this.takeLast(length - suffixLength)
   }
}