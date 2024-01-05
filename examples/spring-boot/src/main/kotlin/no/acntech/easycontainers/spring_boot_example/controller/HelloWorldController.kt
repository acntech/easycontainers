package no.acntech.easycontainers.spring_boot_example.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HelloWorldController {

    @GetMapping("/hello")
    fun sayHello(): String {
        println("Saying hello!!!!!!!!!!!!!!!!!!!!!!!")
        return "Hello World"
    }
}
