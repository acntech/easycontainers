package no.acntech.easycontainers.spring_boot_example.config

import no.acntech.easycontainers.model.Container
import no.acntech.easycontainers.ContainerFactory
import no.acntech.easycontainers.k8s.K8sUtils
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.output.Slf4JOutputLineCallback
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ContainerConfig {

    private val log = LoggerFactory.getLogger(ContainerConfig::class.java)

    @Bean
    fun container(): Container {
        val container = ContainerFactory.kubernetesContainer {
            withName(ContainerName.of("alpine-httpd-test"))
            withNamespace(Namespace.TEST)
            withImage(ImageURL.of("localhost:5000/test/alpine-simple-httpd:latest"))
            withExposedPort(PortMappingName.HTTP, NetworkPort.HTTP)
            withVolume(VolumeName.of("kaniko-data"), UnixDir.of("/mnt/kaniko-data"))
            withIsEphemeral(true)
            withLogLineCallback(
                Slf4JOutputLineCallback(
                    logger = LoggerFactory.getLogger("no.acntech.alpine-httpd-test"),
                    prefix = "HTTPD-CONTAINER-OUTPUT: "
                )
            )
            if (K8sUtils.isRunningOutsideCluster()) {
                withPortMapping(NetworkPort.HTTP, NetworkPort.of(31080))
            }
        }.build()
        container.run()
        return container.also {
            log.info("Container created and started successfully: $it")
        }
    }

}