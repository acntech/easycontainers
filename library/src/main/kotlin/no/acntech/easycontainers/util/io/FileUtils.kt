package no.acntech.easycontainers.util.io

object FileUtils {

    private val UNIX_COMPLETE_PATH_REGEX = Regex("^/([^/ ]+/)*[^/ ]+$")

    fun isCompleteUnixPath(path: String): Boolean {
        return path.matches(UNIX_COMPLETE_PATH_REGEX)
    }

}