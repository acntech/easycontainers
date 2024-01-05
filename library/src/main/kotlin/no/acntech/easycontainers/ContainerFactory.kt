package no.acntech.easycontainers

object ContainerFactory {

    fun container(block: ContainerBuilder.() -> Unit): ContainerBuilder {
        val builder = ContainerBuilder()
        builder.block()
        return builder
    }

    fun dockerContainer(block: ContainerBuilder.() -> Unit): ContainerBuilder {
        return container {
            withType(ContainerBuilder.ContainerType.DOCKER)
            block()
        }
    }

    fun kubernetesContainer(block: ContainerBuilder.() -> Unit): ContainerBuilder {
        return container {
            withType(ContainerBuilder.ContainerType.KUBERNETES)
            block()
        }
    }

}