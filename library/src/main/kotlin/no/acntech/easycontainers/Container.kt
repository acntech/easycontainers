package no.acntech.easycontainers

interface Container {

    enum class State {
        CREATED,
        STARTED,
        RUNNING,
        FAILED,
        UNKNOWN,
        STOPPED,
        REMOVED
    }

    fun getState(): State

    // Name

    fun getName(): String

    fun getNamespace(): String?

    // Environment

    fun getEnv(): Map<String, String>

    // Command

    fun getCommand(): String // Might be an empty string

    fun getArgs(): List<String>

    // Image

    fun getImage(): String

    // Ports

    fun getExposedPorts(): List<Int>

    fun getMappedPort(port: Int): Int

    fun getPortMappings(): Map<Int, Int>

    fun hasPortMapping(port: Int): Boolean {
        return getPortMappings().containsKey(port)
    }

    // Ephemerality
    fun isEphemeral(): Boolean

    // Host
    fun getHost(): String?

    // Lifecycle

    fun start()

    fun stop()

    fun remove()

}