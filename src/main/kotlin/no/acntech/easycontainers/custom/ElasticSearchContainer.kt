package no.acntech.easycontainers.custom

import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.text.COLON
import no.acntech.easycontainers.util.text.NEW_LINE


class ElasticSearchContainer(
   builder: ElasticSearchContainerBuilder,
) : GenericContainer(builder) {

   class ElasticSearchContainerBuilder : GenericContainerBuilder() {

      companion object {
         const val IMAGE = "docker.elastic.co/elasticsearch/elasticsearch"
      }

      internal var version = ImageTag.LATEST.value

      init {
         executionMode = ExecutionMode.SERVICE
         withName(ContainerName.of("elasticsearch-test"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("$IMAGE$COLON${ImageTag.LATEST}"))
         withEnv("discovery.type", "single-node")
         withEnv("xpack.security.enabled", "false")
         withEnv("xpack.security.http.ssl.enabled", "false")
         withEnv("xpack.security.transport.ssl.enabled", "false")
         withEnv("CLUSTER_NAME", "dev-cluster")
         withEnv("NODE_NAME", "dev-node")
         withEnv("ELASTIC_PASSWORD", "passwd")
         withEnv("ES_JAVA_OPTS", "-Xms1024m -Xmx1024m")
         withEnv("ES_DEV_MODE", "true")
         withEnv("ES_LOG_LEVEL", "DEBUG")
         withExposedPort(PortMappingName.HTTP, NetworkPort.of(9200))
         withExposedPort(PortMappingName.TRANSPORT, NetworkPort.of(9300))
         withIsEphemeral(true)
         withOutputLineCallback { line -> println("ELASTIC-OUTPUT: $line") }
      }

      fun withVersion(version: SemanticVersion): ElasticSearchContainerBuilder {
         withImage(ImageURL.of("$IMAGE$COLON$version"))
         return self()
      }

      override fun self(): ElasticSearchContainerBuilder {
         return this
      }

      override fun build(): Container {
         checkBuildAllowed()

         val httpPort = if (containerPlatformType == ContainerPlatformType.KUBERNETES) NetworkPort.of(30200)
         else NetworkPort.of(9200)

         val transportPort = if (containerPlatformType == ContainerPlatformType.KUBERNETES) NetworkPort.of(30300)
         else NetworkPort.of(9300)

         withPortMapping(NetworkPort.of(9200), httpPort)
         withPortMapping(NetworkPort.of(9300), transportPort)

         return ElasticSearchContainer(this)
      }

   }

   companion object {

      fun builder(): ElasticSearchContainerBuilder {
         return ElasticSearchContainerBuilder()
      }

   }

   init {
      log.debug("ElasticSearchContainer created with builder:$NEW_LINE$builder")
   }

}