package no.acntech.easycontainers.spring_boot_example.config

import no.acntech.easycontainers.Container
import no.acntech.easycontainers.ContainerFactory
import no.acntech.easycontainers.k8s.K8sUtils
import no.acntech.easycontainers.output.Slf4jLineCallback
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ContainerConfig {

    private val log = LoggerFactory.getLogger(ContainerConfig::class.java)

    @Bean
    fun container(): Container {
        val container = ContainerFactory.kubernetesContainer {
            withName("alpine-httpd-test")
            withNamespace("test")
            withImage("localhost:5000/alpine-simple-httpd:latest")
            withExposedPort("http", 80)
            withVolume("kaniko-data", "/mnt/kaniko-data")
            withIsEphemeral(true)
            withLogLineCallback(
                Slf4jLineCallback(
                    logger = LoggerFactory.getLogger("no.acntech.alpine-httpd-test"),
                    prefix = "HTTPD-CONTAINER-OUTPUT: "
                )
            )
            if (K8sUtils.isRunningOutsideCluster()) {
                withPortMapping(80, 31080)
            }
        }.build()
        container.start()
        return container.also {
            log.info("Container created and started successfully: $it")
        }
    }

}