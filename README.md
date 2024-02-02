# Easycontainers

## Table of Contents

1. [Introduction](#Introduction)
2. [Implemented features](#Implemented-features)
3. [Usage](#Usage)
    1. [Kubernetes](#Kubernetes)
    2. [Docker](#Docker)
4. [Example applications](#Example-applications)
5. [TODO](#TO-DO)
6. [Contributing](#contributing)
7. [License](#license)

## Introduction
 
Easycontainers is a Kotlin library that allows you to easily create and manage (generic) containers in Kubernetes and Docker. 

The inspiration for this library came from the [testcontainers](https://www.testcontainers.org/) library, which is a Java library that allows you to easily create and manage containers for testing purposes. However, the testcontainers library is not (always) suitable for production use (i.e. in CI pipeline), as it is not possible to create containers in Kubernetes without a DinD solution. 

This library aims to provide a similar experience (albeit limited) as the Testcontainers library, but with the added benefit of being able to create containers in Kubernetes.

## Implemented features
* Create and manage (simple) containers in Kubernetes
* Build custom containers from (programmatically constructoed) Dockerfiles and push them to a Docker registry - the K8s implementation deploys a Kaniko jod to build the container and push to a chosen registry.

## Usage

### Kubernetes

The following example shows how to create a (test) Elasticsearch container in Kubernetes:

```kotlin
@Test
fun doElasticsearchStuff() {
    val container = ContainerFactory.kubernetesContainer {       
        withName("elasticsearch-test")
        withNamespace("test")
        withImage("docker.elastic.co/elasticsearch/elasticsearch:8.11.3")
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
        withExposedPort("http", 9200)
        withExposedPort("transport", 9300)
        withPortMapping(9200, 30200)
        withPortMapping(9300, 30300)
        withIsEphemeral(true)
        withLogLineCallback { line -> println("ELASTIC-OUTPUT: $line") }
    }.build()
    container.start()
   
    // Do something with the container
   
    container.stop()
    container.remove()
}
```

The following example shows how to build and deploy a custom container in Kubernetes:

```kotlin

private const val REGISTRY = "172.23.75.43:5000" // Replace with your own registry

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

fun buildAndRunCustomContainer() {
    val imageName = "simple-alpine"
    
    DockerRegistryUtils.deleteImage("http://$REGISTRY", imageName)
    
    val tempDir = Files.createTempDirectory("dockercontext-").toString()
    log.debug("Temp dir for docker-context created: {}", tempDir)
    val dockerfile = File(tempDir, "Dockerfile")
    val logTimeScript = File(tempDir, "log_time.sh")
    dockerfile.writeText(dockerfileContent)
    logTimeScript.writeText(logTimeScriptContent)
    
    log.debug("Dockerfile created: {}", dockerfile.absolutePath)
    log.debug("log_time.sh created: {}", logTimeScript.absolutePath)
    
    val imageBuilder = K8sContainerImageBuilder()
    .withName(imageName)
    .withImageRegistry("http://$REGISTRY")
    .withNamespace("test")
    .withDockerContextDir(File(tempDir).absolutePath)
    .withLogLineCallback { line -> println("KANIKO-JOB-OUTPUT: ${Instant.now()} $line") }
    
    imageBuilder.buildImage()
    
    // Test deploying it
    val container = ContainerFactory.kubernetesContainer {
        withName("simple-alpine-test")
        withNamespace("test")
        withImage("$REGISTRY/$imageName:latest")
        withIsEphemeral(true)
        withLogLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
    }.build()
    
    container.start()
    
    TimeUnit.SECONDS.sleep(120)
    
    container.stop()
    container.remove()
}
   ```

## Example applications
See the [examples](examples) folder for examples on how to use the library.

## TO-DO
* Create specific container implementations for well-known services, e.g. Elasticsearch, Kafka, Redis, etc.
* Add Docker container support


## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

## License
2024, Accenture Inc. All Rights Reserved

This software is provided "as is" without warranty of any kind, either expressed or implied, including, but not limited to, the implied warranties of merchantability and fitness for a particular purpose. In no event shall Accenture Inc or its contributors be liable for any direct, indirect, incidental, special, exemplary, or consequential damages (including, but not limited to, procurement of substitute goods or services; loss of use, data, or profits; or business interruption) however caused and on any theory of liability, whether in contract, strict liability, or tort (including negligence or otherwise) arising.