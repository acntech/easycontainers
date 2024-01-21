package no.acntech.easycontainers.spring_boot_example.service

import no.acntech.easycontainers.Container
import no.acntech.easycontainers.ContainerFactory
import no.acntech.easycontainers.ContainerType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant

@Service
class ContainerService(
   private val container: Container,
   @Value("\${registry.url}") private val registryUrl: String,
) {

   private val log = LoggerFactory.getLogger(javaClass)

   private val dockerfileContent = """       
        FROM alpine:latest     
        COPY log_time.sh /usr/local/bin/log_time.sh     
        RUN chmod +x /usr/local/bin/log_time.sh
        CMD sh -c "/usr/local/bin/log_time.sh"
    """.trimIndent()

   private val logTimeScriptContent = """
         #!/bin/sh
         while true; do
               echo "The time is now $(date)"
               sleep 2
         done
      """.trimIndent()

   init {
      log.info("ContainerService created with container: $container")
   }

   fun stop() {
      container.stop()
   }

   fun delete() {
      container.remove()
   }

   fun getIndexPage(): String {
      val host: String? = container.getHost().also {
         log.info("Container host: $it")
      }
      return fetchWebPage("http://$host/index.html")
   }

   private fun fetchWebPage(url: String): String {
      log.trace("Fetching web page: $url")
      try {
         val client = HttpClient.newHttpClient()
         val request = HttpRequest.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .uri(URI.create(url))
            .build()

         val response = client.send(request, HttpResponse.BodyHandlers.ofString())

         val body = response.body();

         if (response.statusCode() == 200) {
            println("Content of the web page:\n${body}")
         } else {
            println("Failed to fetch the web page. Status code: ${response.statusCode()}")
         }
         return body
      } catch (e: Exception) {
         log.error("Failed to fetch web page", e)
      }
      return ""
   }

   /**
    * This method assumes that if we're running outside k8s, we have access to a folder that is shared with the k8s cluster.
    * If we're running inside k8s, this folder should be mounted as a volume in /mnt/kaniko-data. The kaniko job will be deployed
    * with a PV and PVC that will mount the same volume in the same location.
    */
   fun buildImage(): Boolean {
      val tempDir = Files.createTempDirectory("dockercontext-").toString()
      val dockerfile = File(tempDir, "Dockerfile")
      val logTimeScript = File(tempDir, "log_time.sh")
      dockerfile.writeText(dockerfileContent)
      logTimeScript.writeText(logTimeScriptContent)

      val imageBuilder = ContainerFactory.imageBuilder(ContainerType.KUBERNETES)
         .withName("alpine-test")
         .withImageRegistry(registryUrl)
         .withNamespace("test")
         .withDockerContextDir(tempDir)
         .withLogLineCallback { line -> println("KANIKO-JOB-OUTPUT: ${Instant.now()} $line") }

      return imageBuilder.buildImage()
   }

}

// http://alpine-test-service.test.svc.cluster.local/index.html