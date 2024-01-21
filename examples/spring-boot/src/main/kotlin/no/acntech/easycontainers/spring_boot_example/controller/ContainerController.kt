package no.acntech.easycontainers.spring_boot_example.controller


import no.acntech.easycontainers.spring_boot_example.service.ContainerService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ContainerController(
    private val containerService: ContainerService,
) {

    @GetMapping("/stop")
    fun stop(): String {
        containerService.stop()
        return "Container stopped"
    }

    @GetMapping("/delete")
    fun delete(): String {
        containerService.delete()
        return "Container deleted $containerService"
    }

    @GetMapping("/connect")
    fun connect(): String {
        val page = containerService.getIndexPage()
        println("Page: $page")
        return "Container returned:\n\n$page"
    }

    @GetMapping("/build-image")
    fun buildImage(): String {
         val result = containerService.buildImage()
         return "Image built: $result"
    }

}
