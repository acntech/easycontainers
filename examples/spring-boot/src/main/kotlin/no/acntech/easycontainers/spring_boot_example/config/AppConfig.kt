package no.acntech.easycontainers.spring_boot_example.config

import no.acntech.easycontainers.util.collections.prettyPrint
import no.acntech.easycontainers.util.collections.toCensoredCopy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.util.*

@Configuration
class AppConfig {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val appPropertiesMap = HashMap<String, String>()

    @EventListener
    fun handleContextRefreshed(event: ContextRefreshedEvent) {
        val env = event.applicationContext.environment
        if (env is ConfigurableEnvironment) {
            extractProperties(env)
        }
    }

    @EventListener
    fun onStartup(event: ApplicationReadyEvent) {
        log.info("Spring environment properties {\n{}\n}", appPropertiesMap.toCensoredCopy().prettyPrint())
    }

    @Bean(name = [Qualifiers.SPRING_PROPERTIES])
    fun springProperties(): Map<String, String> = appPropertiesMap

    private fun extractProperties(env: ConfigurableEnvironment) {
        log.info("Extracting props from env [{}]", env)

        env.propertySources.forEach { propSrc ->
            if (propSrc is MapPropertySource) {
                val map = TreeMap<String, String>()

                propSrc.source.keys.forEach { key ->
                    map[key] = env.getProperty(key)!!

                    if(key == "spring.application.name") {
                        System.setProperty("spring.application.name", env.getProperty(key)!!)
                    }
                }

                appPropertiesMap.putAll(map)

                log.trace(
                    "Properties generated from source [{}] of type [{}]\n{\n{}\n}",
                    propSrc,
                    propSrc.javaClass.name,
                    map.toCensoredCopy().prettyPrint()
                )
            }
        }
    }

}
