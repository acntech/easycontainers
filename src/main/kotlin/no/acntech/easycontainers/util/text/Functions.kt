package no.acntech.easycontainers.util.text

val WHITESPACE_REGEX = "\\s+".toRegex()

fun String.splitOnWhites() = split(WHITESPACE_REGEX)