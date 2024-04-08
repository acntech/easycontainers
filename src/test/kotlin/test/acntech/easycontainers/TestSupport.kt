package test.acntech.easycontainers

import no.acntech.easycontainers.GenericContainer
import no.acntech.easycontainers.docker.DockerConstants
import no.acntech.easycontainers.kubernetes.K8sUtils
import no.acntech.easycontainers.model.*
import no.acntech.easycontainers.util.net.NetworkUtils
import no.acntech.easycontainers.util.platform.PlatformUtils
import org.junit.jupiter.api.Assertions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TestSupport {

   private val log: Logger = LoggerFactory.getLogger(TestSupport::class.java)

   // NOTE: Only valid for a Windows/WSL2 environment
   val registryIpAddress = PlatformUtils.getWslIpAddress() ?: "localhost"

   val nodeHostIpAddress = registryIpAddress

   val dockerHostAddress = registryIpAddress

   val registry = "${registryIpAddress}:5000"

   val localKanikoPath = PlatformUtils.convertLinuxPathToWindowsWslPath("/home/thomas/kind/kaniko-data")

   private val DOCKERFILE_CONTENT = """       
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
         COPY log-time.sh /usr/local/bin/log-time.sh
         
         # Make the log-time.sh script executable
         RUN chmod +x /usr/local/bin/log-time.sh
         
         # Create a startup script directly in the Dockerfile
         RUN echo '#!/bin/sh' > /start.sh \
            && echo '/usr/sbin/sshd &' >> /start.sh \
            && echo '/usr/local/bin/log-time.sh' >> /start.sh \
            && chmod +x /start.sh

         # Expose necessary ports (the SSH port)
         EXPOSE 22
         
         # Define the container's default behavior
         CMD ["/start.sh"]

    """.trimIndent()

   private val LOG_TIME_SCRIPT_CONTENT = """
         count=1
         while true
         do
           echo "${'$'}{count}: ${'$'}(date)"
           count=${'$'}((count+1))
           sleep 1
         done
      """.trimIndent()

   init {
      System.setProperty(DockerConstants.PROP_DOCKER_HOST, "tcp://$dockerHostAddress:2375")
   }

   fun startContainer(
      platform: ContainerPlatformType = ContainerPlatformType.DOCKER,
      executionMode: ExecutionMode = ExecutionMode.SERVICE,
      ephemeral: Boolean = true,
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
         withName(ContainerName.of("easycontainers-$imageName"))
         withNamespace(Namespace.TEST)
         withImage(ImageURL.of("$registry/test/$imageName:latest"))

         withExecutionMode(executionMode)

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
         withContainerPlatformType(platform)
      }.build()

      log.debug("Container created: $container")

      val runtime = container.getRuntime()
      log.debug("Container runtime: $runtime")

      runtime.start()

      Assertions.assertTrue(container.getState() == ContainerState.INITIALIZING || container.getState() == ContainerState.RUNNING)

      // Wait for the container to be in the running state
      container.waitForState(ContainerState.RUNNING, 30, TimeUnit.SECONDS)

      Assertions.assertEquals(ContainerState.RUNNING, runtime.getContainer().getState())

      Assertions.assertTrue(NetworkUtils.isPortOpen("localhost", mappedLocalHttpPort))
      Assertions.assertTrue(NetworkUtils.isPortOpen("localhost", mappedLocalSshPort))

      return container
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

}