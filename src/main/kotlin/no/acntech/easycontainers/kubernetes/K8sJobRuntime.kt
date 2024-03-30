package no.acntech.easycontainers.kubernetes

import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import no.acntech.easycontainers.GenericContainer
import java.util.*

class K8sJobRuntime(
   container: GenericContainer,
   client: KubernetesClient = K8sClientFactory.createDefaultClient(),
) : K8sRuntime(container, client) {

   private val uuid: String = UUID.randomUUID().toString()

   private val jobName = "${container.getName()}-$uuid"

   private var job: Job? = null

   override fun start() {
      TODO("Not yet implemented")
   }

   override fun stop() {
      TODO("Not yet implemented")
   }

   override fun delete(force: Boolean) {
      TODO("Not yet implemented")
   }

   private fun createAndDeployJob(contextDir: String) {
      job = client
         .batch()
         .v1()
         .jobs()
         .inNamespace(container.getNamespace().unwrap())
         .resource(job)
         .create().also {
            log.info("Kubernetes job '$it' deployed in namespace '${getNamespace()}'")
         }
   }

}
