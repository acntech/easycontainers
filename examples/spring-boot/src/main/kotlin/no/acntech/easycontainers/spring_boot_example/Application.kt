package no.acntech.easycontainers.spring_boot_example

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import java.time.Instant

@SpringBootApplication
class Application

private val log = LoggerFactory.getLogger(Application::class.java)

fun main(args: Array<String>) {
    SpringApplication.run(Application::class.java, *args)
}

@PreDestroy
private fun destroy() {
    log.warn("@PreDestroy callback: Application terminating at ${Instant.now()}")
}