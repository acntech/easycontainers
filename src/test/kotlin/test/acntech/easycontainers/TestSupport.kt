package test.acntech.easycontainers

import no.acntech.easycontainers.ContainerBuilderCallback
import no.acntech.easycontainers.Environment
import no.acntech.easycontainers.Environment.defaultRegistryEndpoint
import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.kubernetes.K8sUtils
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.lang.guardedExecution
import no.acntech.easycontainers.util.net.NetworkUtils
import no.acntech.easycontainers.util.platform.PlatformUtils
import no.acntech.easycontainers.util.text.CRLF
import no.acntech.easycontainers.util.text.NEW_LINE
import org.junit.jupiter.api.Assertions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TestSupport {

   private val log: Logger = LoggerFactory.getLogger(TestSupport::class.java)

   val localKanikoDir = PlatformUtils.convertUnixPathToWindowsWslPath(Environment.k8sKanikoDataHostDir)

   val localHostShareDir = PlatformUtils.convertUnixPathToWindowsWslPath(Environment.k8sGeneralDataHostDir)

   val dockerFileContent = """       
         # Use Alpine Linux as the base image
         FROM alpine:latest

         # Install dependencies
         # RUN apk add --no-cache curl netcat-openbsd openssh
         RUN apk add --no-cache openssh

         # Additional setup SSH
         RUN sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config \
             && sed -i 's/#PasswordAuthentication yes/PasswordAuthentication yes/' /etc/ssh/sshd_config \
             && echo "root:root" | chpasswd \
             && ssh-keygen -A
         
         # Copy the log-time.sh script to the container
         COPY log-time.sh /log-time.sh
         
         # Make the log-time.sh script executable
         RUN chmod +x /log-time.sh
         
         # Create a startup script directly in the Dockerfile
         RUN echo '#!/bin/sh' > /entrypoint.sh \
            && echo '/usr/sbin/sshd &' >> /entrypoint.sh \
            && echo '/log-time.sh' >> /entrypoint.sh \
            && chmod +x /entrypoint.sh

         # Expose necessary ports (the SSH port)
         EXPOSE 22
         
         # Define the container's default behavior
         CMD ["/entrypoint.sh"]

      """.trimIndent().replace(CRLF, NEW_LINE)

   val scriptContent = """
         #!/bin/sh
         
         count=1
         while true
         do
           echo "${'$'}{count}: ${'$'}(date) - #MESSAGE#"
           if [ "${'$'}count" -eq #COUNT# ]; then
             echo "Exiting!"
             exit 10
           fi
           count=${'$'}((count+1))
           sleep 1
         done
      """.trimIndent().replace(CRLF, NEW_LINE)


   fun startContainer(
      platform: ContainerPlatformType = ContainerPlatformType.DOCKER,
      executionMode: ExecutionMode = ExecutionMode.SERVICE,
      ephemeral: Boolean = true,
      containerBuilderCallback: ContainerBuilderCallback? = null,
   ): Container {
      val imageName = "container-test"

      log.info("Testing the image '$imageName' on '$platform' with '$executionMode' mode")

      val mappedLocalHttpPort = when (platform) {
         ContainerPlatformType.DOCKER -> 8080
         ContainerPlatformType.KUBERNETES -> if (K8sUtils.isRunningOutsideCluster()) 30080 else 80
      }

      val mappedLocalSshPort = when (platform) {
         ContainerPlatformType.DOCKER -> 8022
         ContainerPlatformType.KUBERNETES -> if (K8sUtils.isRunningOutsideCluster()) 30022 else 22
      }

      val container = GenericContainer.builder().apply {
         withContainerPlatformType(platform)
         withName(ContainerName.of("easycontainers-$imageName"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("$defaultRegistryEndpoint/test/$imageName:latest"))

         withExecutionMode(executionMode)

         withEnv("LOG_TIME_MESSAGE", "Hello from $platform-container running as $executionMode")

         when (executionMode) {

            ExecutionMode.SERVICE -> {
               withEnv("LOG_TIME_SLEEP", "1")
               withEnv("LOG_TIME_EXIT_FLAG", "false")
            }

            ExecutionMode.TASK -> {
               withEnv("LOG_TIME_SLEEP", "1")
               withEnv("LOG_TIME_EXIT_FLAG", "true")
               withEnv("LOG_TIME_EXIT_CODE", "10")
               withEnv("LOG_TIME_ITERATIONS", "5")
            }
         }

         // HTTP
         withExposedPort(PortMappingName.HTTP, NetworkPort.HTTP)
         withPortMapping(NetworkPort.HTTP, NetworkPort.of(mappedLocalHttpPort))

         // SSH
         withExposedPort(PortMappingName.SSH, NetworkPort.SSH)
         withPortMapping(NetworkPort.SSH, NetworkPort.of(mappedLocalSshPort))

         withIsEphemeral(ephemeral)
         withOutputLineCallback { line -> println("$platform-'${imageName.uppercase()}'-CONTAINER-OUTPUT: $line") }

         containerBuilderCallback?.configure(this)

      }.build()

      log.debug("Container created: $container")

      val runtime = container.getRuntime()
      log.debug("Container runtime: $runtime")

      runtime.start()

      Assertions.assertTrue(container.getState() == Container.State.INITIALIZING || container.getState() == Container.State.RUNNING)

      // Wait for the container to reach the running state
      container.waitForState(Container.State.RUNNING, 30, TimeUnit.SECONDS)

      Assertions.assertEquals(Container.State.RUNNING, runtime.getContainer().getState())

      Assertions.assertTrue(NetworkUtils.isTcpPortOpen("localhost", mappedLocalHttpPort))
      Assertions.assertTrue(NetworkUtils.isTcpPortOpen("localhost", mappedLocalSshPort))

      return container
   }

   fun shutdownContainer(container: Container) {
      val runtime = container.getRuntime()
      guardedExecution({ runtime.stop() })
      runtime.delete(true)
      Assertions.assertEquals(Container.State.DELETED, runtime.getContainer().getState())
   }

   fun monitorDeadlocks() {
      val scheduledExecutorService = Executors.newScheduledThreadPool(1)
      val deadlockMonitor = Runnable {
         val threadBean = ManagementFactory.getThreadMXBean()
         log.debug("Checking for deadlocks")
         val deadlockedThreads = threadBean.findDeadlockedThreads()

         deadlockedThreads?.let {
            it.forEach { id ->
               val threadInfo = threadBean.getThreadInfo(id)
               log.warn("Deadlocked thread: $threadInfo")
            }
         }

         printNonSystemNonDaemonThreads()
      }
      scheduledExecutorService.scheduleAtFixedRate(deadlockMonitor, 0, 3, TimeUnit.SECONDS)
   }

   fun printNonSystemNonDaemonThreads() {
      val threadSet = Thread.getAllStackTraces().keys

      for (thread in threadSet) {
         if (!thread.isDaemon && thread.threadGroup.name != "system") {
            val stackTraceElements = thread.stackTrace
            val threadInfo = "${thread.name}\n"
            val elementsInfo = stackTraceElements.joinToString("\n") { "\tat $it" }
            log.debug("$threadInfo$elementsInfo")
         }
      }
   }

   fun getActualDir(hostDir: Path): Path {
      return Path.of(
         if (PlatformUtils.isWslInstalled()) {
            PlatformUtils.convertUnixPathToWindowsWslPath(hostDir.toString())
         } else {
            hostDir.toString()
         }
      )
   }

}