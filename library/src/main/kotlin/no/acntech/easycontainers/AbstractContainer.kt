package no.acntech.easycontainers

import no.acntech.easycontainers.util.text.EMPTY_STRING
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class AbstractContainer(
    protected val builder: ContainerBuilder,
) : Container {

    protected val log: Logger = LoggerFactory.getLogger(javaClass)

    private var state: Container.State = Container.State.CREATED

    protected var internalHost: String? = null

    init {
        if (builder.isEphemeral) {
            Runtime.getRuntime().addShutdownHook(Thread {
                log.info("ShutdownHook: stopping container [${getName()}]")
                stop()
                remove()
            })
        }
    }

    @Synchronized
    override fun getState(): Container.State {
        return state
    }

    override fun getName(): String {
        return builder.name
    }

    override fun getNamespace(): String? {
        return builder.namespace
    }

    override fun getLabels(): Map<String, String> {
        return builder.labels.toMap()
    }

    override fun getEnv(): Map<String, String> {
        return builder.env.toMap()
    }

    override fun getCommand(): String {
        return builder.command?.trim() ?: EMPTY_STRING
    }

    override fun getArgs(): List<String> {
        return builder.args.toList()
    }

    override fun getImage(): String {
        return builder.image ?: throw IllegalStateException("Image is not set")
    }

    override fun getExposedPorts(): List<Int> {
        return builder.exposedPorts.values.toList()
    }

    override fun getMappedPort(port: Int): Int {
        return builder.portMappings[port] ?: throw IllegalArgumentException("Port [$port] is not mapped")
    }

    override fun getPortMappings(): Map<Int, Int> {
        return builder.portMappings.toMap()
    }

    override fun isEphemeral(): Boolean {
        return builder.isEphemeral
    }

    override fun getHost(): String? {
        return internalHost
    }

    override fun toString(): String {
        return ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("state", state)
            .append("name", getName())
            .append("namespace", getNamespace())
            .append("env", getEnv())
            .append("command", getCommand())
            .append("image", getImage())
            .append("exposedPorts", getExposedPorts())
            .append("mappedPorts", getPortMappings())
            .append("isEphemeral", isEphemeral())
            .append("host", getHost())
            .toString()
    }

    @Synchronized
    protected fun changeState(newState: Container.State) {
        if (newState == state || state == Container.State.REMOVED) {
            return
        }

        val oldState = state
        state = newState
        log.info("Container [${getName()}] changed state from [$oldState] to [$state]")
    }

}