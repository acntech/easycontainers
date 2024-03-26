# Easycontainers

## Table of Contents

1. [Introduction](#Introduction)
2. [Getting Started](#Getting-Started)
   1. [Starting a container](#Starting-a-container)
   2. [Building and deploying a custom container](#Building-and-deploying-a-custom-container)
3. [Requirements](#Requirements)
   1. [Docker](#Docker)
   2. [Kubernetes](#Kubernetes)
4. [Configuration](#Configuration)
   1. [Docker specifics](#Docker-specifics)
   2. [Kubernetes specifics](#Kubernetes-specifics)
5. [Examples](#Examples)
   1. [Docker examples](#Docker-examples)
   2. [Kubernetes examples](#Kubernetes-examples)
6. [API Reference](#API-Reference)
7. [Known Issues](#Known-Issues)
8. [Troubleshooting](#Troubleshooting)
9. [FAQs](#FAQs)
10. [Community and Support](#Community-and-Support)
11. [Acknowledgments](#Acknowledgments)
12. [Roadmap](#Roadmap)
13. [Contributing](#Contributing)
14. [License](#License)

## Introduction
 
Easycontainers is a powerful Kotlin library that allows you to easily create and manage containers in Kubernetes and Docker using a simple and intuitive common platform-agnostic API. 

The inspiration for this library came from the [testcontainers](https://www.testcontainers.org/) library, which is a Java library that allows you to create and manage (ephimeral) containers for testing purposes. However, the Testcontainers library is not (always) suitable for production use (i.e. in CI pipeline), as it is not possible to create containers in Kubernetes without a DinD solution. 

This library aims to provide a similar experience (albeit limited) as the Testcontainers library, but with the added benefit of being able to create 
and manage containers also in Kubernetes. In addition it has the benefit of not being tied to a Junit-test life-cycle, and hence useful for any kind of container management, also beyond testing.

## Getting Started

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

Note that the ports are mapped via NodePort when running the test from outside the cluster. If you run the code from a pod inside the cluster, the mapped ports can be the same as the exposed ports.
<p>
To run the above example using Docker as the target container platform, just replace the `withContainerPlatformType(ContainerPlatformType.KUBERNETES)` with `withContainerPlatformType(ContainerPlatformType.DOCKER)`. 

### Building and deploying a custom container

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
    log.debug("Temp [.gitignore](.gitignore)dir for docker-context created: {}", tempDir)
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

## Requirements

### Docker
Developed using Docker [version 24.0.5](https://docs.docker.com/engine/release-notes/24.0/).

### Kubernetes
Developed using Kubernetes [version 1.29.2](https://kubernetes.io/releases/)  on Kind [version 0.22](https://github.com/kubernetes-sigs/kind/releases).

## Configuration
See the test-env folder for scripts and resources to set up a test environment for running the tests.

### Docker specifics

### Kubernetes specifics
In the current version, when running outside a k8s cluster, the Kubernetes runtime only supports using the default kubeconfig file located in the user's home directory. If using the library inside a Kubernetes cluster (i.e. in a pod), the Fabric8 default approach is used to authenticate the client, i.e. the mounted service account token at `/var/run/secrets/kubernetes.io/serviceaccount/token`.

## Examples

### Docker examples
TODO

### Kubernetes examples
TODO

## API Reference
[JavaDoc](https://blog.acntech.no/easycontainers/index.html)

## Known Issues
Currently neither the Kubernetes nor the Docker container runtime implementation supports exeucting commands with stdin input. For Docker, this is due to the fact that the Docker API does not support this feature. For Docker, this is due to a "hijacking session" issue that is not yet resolved. For Kubernetes, this is due to the ExecWatch::getOutput() method is for some reason always null.

## Troubleshooting
TODO

## FAQs
TODO

## Community and Support
TODO

## Acknowledgments
TODO

## Roadmap
- Convert all tests to use [Testcontainers](https://testcontainers.com/) - either the official [K3s module](https://java.testcontainers.org/modules/k3s/), or the community contributed [KinD module](https://testcontainers.com/modules/kindcontainer/).

## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

## License
This software is licensed under the Apache 2 license, see LICENSE and NOTICE for details.