# Easycontainers

## Table of Contents

1. [Introduction](#Introduction)
2. [Usage](#Usage)
   1. [Starting a container](#Starting-a-container)
   2. [Building a custom container](#Building-a-custom-container)
   2. [Kubernetes specifics](#Kubernetes-specifics)
   3. [Docker specifics](#Docker-specifics)
3. [Limitations](#Limitations) 
4. [Contributing](#Contributing)
5. [License](#License)

## Introduction
 
Easycontainers is a powerful Kotlin library that allows you to easily create and manage containers in Kubernetes and Docker using a simple and intuitive common platform-agnostic API. 

The inspiration for this library came from the [testcontainers](https://www.testcontainers.org/) library, which is a Java library that allows you to create and manage (ephimeral) containers for testing purposes. However, the Testcontainers library is not (always) suitable for production use (i.e. in CI pipeline), as it is not possible to create containers in Kubernetes without a DinD solution. 

This library aims to provide a similar experience (albeit limited) as the Testcontainers library, but with the added benefit of being able to create 
and manage containers also in Kubernetes. In addition it has the benefit of not being tied to a Junit-test life-cycle, and hence useful for any kind of container management, also beyond testing.

## Usage

### Starting a container

The following example shows how to create a (test) Elasticsearch container in Kubernetes:

```kotlin
@Test
fun doElasticsearchStuff() {
    val container = GenericContainer.builder().apply {      
        withContainerPlatformType(ContainerPlatformType.KUBERNETES)
        withName(ContainerName.of("elasticsearch-test"))
        withNamespace(Namespace.of("test"))
        withImage(ImageURL.of("docker.elastic.co/elasticsearch/elasticsearch:8.11.3"))
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
        withExposedPort(PortMappingName.TRANSPORT, 9300)
        withPortMapping(NetworkPort.of(9200), NetworkPort.of(30200))
        withPortMapping(NetworkPort.of(9300), NetworkPort.of(30300))
        withIsEphemeral(true)
        withLogLineCallback { line -> println("ELASTIC-OUTPUT: $line") }
    }.build()
    
    container.getRuntime().start()
   
    // Do something with the container
   
    // Stop it
    container.getRuntime().stop()
      
    // Delete it
    container.getRuntime().delete()
}
```

Note that the mapped ports are mapped via NodePort since running the test from outside the cluster. If you run the code from a pod insideh the cluster, the mapped ports can be the same as the exposed ports.
<p>
To run the above example in Docker, just replace the `withContainerPlatformType(ContainerPlatformType.KUBERNETES)` with `withContainerPlatformType(ContainerPlatformType.DOCKER)`. 

### Building a custom container

The following example shows how to build and deploy a custom container in Docker:

```kotlin

private const val REGISTRY = "172.23.75.43:5000" // Replace with your own registry

// Simple Dockerfile
private val dockerfileContent = """       
    FROM alpine:latest     
    COPY log_time.sh /usr/local/bin/log_time.sh     
    RUN chmod +x /usr/local/bin/log_time.sh
    CMD sh -c "/usr/local/bin/log_time.sh"
   """.trimIndent()

// Simple script that logs the time every 2 seconds
private val logTimeScriptContent = """
     #!/bin/sh[pom.xml](library%2Fpom.xml)
     while true; do
           echo "The time is now $(date)"
           sleep 2
     done
     """.trimIndent()

fun buildAndRunCustomContainer() {
    val imageNameVal = "simple-alpine"
    
    DockerRegistryUtils.deleteImage("http://$REGISTRY/test", imageNameVal)
    
    val tempDir = Files.createTempDirectory("dockercontext-").toString()
    log.debug("Temp dir for docker-context created: {}", tempDir)
    val dockerfile = File(tempDir, "Dockerfile")
    val logTimeScript = File(tempDir, "log_time.sh")
    dockerfile.writeText(dockerfileContent)
    logTimeScript.writeText(logTimeScriptContent)
    
    log.debug("Dockerfile created: {}", dockerfile.absolutePath)
    log.debug("log_time.sh created: {}", logTimeScript.absolutePath)
    
    val imageBuilder = K8sContainerImageBuilder()
    .withName(ImageName.of(imageNameVal))
    .withImageRegistry(ImageURL.of("$REGISTRY/test/$imageName:latest"))
    .withNamespace(Namespace.of("test"))
    .withDockerContextDir(File(tempDir).absolutePath)
    .withLogLineCallback { line -> println("KANIKO-JOB-OUTPUT: ${Instant.now()} $line") }
    
    imageBuilder.buildImage()
    // INVARIANT: The image is built and pushed to the registry
    
    // Test deploying the custom image in Docker
   val container = GenericContainer.builder().apply {
        withContainerPlatformType(ContainerPlatformType.DOCKER)
        withName(ContainerName.of("simple-alpine-test"))
        withNamespace(Namespace.of("test"))
        withImage(ImageURL.of("$REGISTRY/test/$imageName:latest"))
        withIsEphemeral(true)
        withLogLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
    }.build()
    
    container.getRuntime().start()
    
    TimeUnit.SECONDS.sleep(120) // Lean back and watch the time being logged every 2 seconds
    
    container.stop()
    container.delete()
}
   ```

To run the above example in Kubernetes, just replace the `withContainerPlatformType(ContainerPlatformType.DOCKER)` with `withContainerPlatformType(ContainerPlatformType.KUBERNETES)`.

### Docker specifics

### Kubernetes specifics

## Limitations

## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

## License
2024, Accenture Inc. All Rights Reserved.

This software is provided "as is" without warranty of any kind, either expressed or implied, including, but not limited to, the implied warranties of merchantability and fitness for a particular purpose. In no event shall Accenture Inc or its contributors be liable for any direct, indirect, incidental, special, exemplary, or consequential damages (including, but not limited to, procurement of substitute goods or services; loss of use, data, or profits; or business interruption) however caused and on any theory of liability, whether in contract, strict liability, or tort (including negligence or otherwise) arising