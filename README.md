# Easycontainers

## Table of Contents

1. [Introduction](#Introduction)
2. [Getting Started](#Getting-Started)
   1. [Prerequisites](#Prerequisites) 
   2. [Starting a container](#Starting-a-container)
   3.  [Building and deploying a custom container](#Building-and-deploying-a-custom-container)
   4. [Transferring files to and from a container](#Transferring-files-to-and-from-a-container)
   5, [Executing commands in a running container](#Running-commands-in-a-running-container)
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

**Easycontainers** is a streamlined Kotlin library, that can be used in both Kotlin and Java projects, designed to facilitate the creation and management of containers across both Kubernetes and Docker environments. By offering a straightforward, platform-agnostic API, it simplifies the complexities typically associated with container orchestration, making it an essential tool for developers looking to enhance their productivity and efficiency.

Born from the practical insights gained from the [Testcontainers](https://www.testcontainers.org/) library, Easycontainers extends these concepts to support not just ephemeral testing containers but also stable production environments. It addresses a critical gap for Kubernetes users by eliminating the need for Docker-in-Docker (DinD) solutions, providing a more integrated and reliable approach to container management.

**Key Features:**
- **Unified API**: Manage Docker and Kubernetes containers with ease, thanks to a cohesive and intuitive API.
- **Flexibility**: Ideal for a wide array of applications, from testing to CI pipelines and production deployments.
- **Independence**: Operates independently of the Junit-test lifecycle, offering versatility beyond just testing scenarios.

With Easycontainers, developers gain a versatile tool that streamlines container management, freeing them to focus more on development and less on the operational intricacies of containers.

## Getting Started

### Prerequisites

#### Kubernetes
For running Kubernetes containers,
access to a Kubernetes cluster is required with a valid kubeconfig file located in the user's home directory.
Make sure the kubeconfig file is correctly configured to access the desired cluster with the necessary permissions.
For a KinD setup, see [kind-env](https://github.com/acntech/easycontainers/tree/main/src/test/resources/env/kind).

#### Docker 
For running Docker containers, access to a Docker daemon is required.
Make sure the Docker daemon is running and accessible from the host machine,
either on localhost or as dictated by the DOCKER_HOST environment variable.
For an example setup, see [docker-env](https://github.com/acntech/easycontainers/tree/main/src/test/resources/env/docker).

#### Development Environment
- Java 21 or higher
- Kotlin 1.9.0 or higher
- Maven 3.8.4 or higher
- KinD 0.22 or higher (for Kubernetes)
- Docker 26.0 or higher (for Docker)
- See example environment setup scripts in the [test-env](https://github.com/acntech/easycontainers/tree/main/src/test/resources/env) folder for running the tests.


### Starting a container

The following example shows how to create a (test) Elasticsearch container in Kubernetes:

```kotlin
@Test
fun doElasticsearchStuff() {
    val container = GenericContainer.builder().apply {      
        withContainerPlatformType(ContainerPlatformType.KUBERNETES)
        withName("elasticsearch-test")
        withNamespace("test")
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
        withPortMapping(9200, 30200)
        withPortMapping(9300, 30300)
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

private const val REGISTRY = "localhost:5000" // Replace with your own registry

// Simple Dockerfile
private val dockerfileContent = """       
    FROM alpine:latest     
    COPY log_time.sh /usr/local/bin/log_time.sh     
    RUN chmod +x /usr/local/bin/log_time.sh
    CMD sh -c "/usr/local/bin/log_time.sh"
   """.trimIndent()

// Simple script that logs the time every 2 seconds
private val logTimeScriptContent = """
     #!/bin/sh
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
       .withImageRegistry("$REGISTRY/test/$imageName:latest")
       .withNamespace("test")
       .withDockerContextDir(File(tempDir).absolutePath)
       .withLogLineCallback { line -> println("KANIKO-JOB-OUTPUT: ${Instant.now()} $line") 
    }
    
    imageBuilder.buildImage()
    // INVARIANT: The image is built and pushed to the registry
    
    // Test deploying the custom image in Docker
   val container = GenericContainer.builder().apply {
        withContainerPlatformType(ContainerPlatformType.DOCKER)
        withName("simple-alpine-test")
        withNamespace("test")
        withImage("$REGISTRY/test/$imageName:latest")
        withIsEphemeral(true)
        withLogLineCallback { line -> println("SIMPLE-ALPINE-OUTPUT: $line") }
    }.build()
    
    val runtime = container.getRuntime()
    runtime.start()
    
    TimeUnit.SECONDS.sleep(120) // Lean back and watch the time being logged every 2 seconds
    
    runtime.stop()
    runtime.delete()
}
   ```

To run the above example in Kubernetes, just replace the `withContainerPlatformType(ContainerPlatformType.DOCKER)` with `withContainerPlatformType(ContainerPlatformType.KUBERNETES)`. This will build the image using a Kubernetes Kaniko-job and then deploy the image as a single container service in Kubernetes.

### Transferring files to and from a container

The following example shows how to transfer a file from the local file system to a running container:

```kotlin
   val localFile = File("hello.txt")
   localFile.writeText("Hello, world!")
   container.putFile(localFile, UnixDir.of("/tmp"))
```

The following example shows how to transfer a (remote) file from a running container to the local file system (a temporary file is created for the downloaded file):

```kotlin
   val path = container.getFile(UnixDir.of("/tmp"), "hello.txt")
   val content = Files.readString(path)
   log.debug("Content of file:\n$content")
```

In order to specify a target file (for the downloaded file), just specify it as an argument:

```kotlin
    val path = container.getFile(UnixDir.of("/tmp"), "hello.txt", Path.of("./hello-local.txt"))
    val content = Files.readString(path)
    log.debug("Content of file:\n$content")
```

The following example shows how to transfer a directory from the local file system to a running container:
```kotlin
    val size = container.putDirectory(Path.of("./resources"), UnixDir.of("/tmp"))
```

The following example shows how to transfer a directory from a running container to the local file system:
```kotlin
    val (parentPath, paths) = container.getDirectory(UnixDir.of("/tmp/resources"), Files.createTempDirectory("container-resources"))
    for (path in paths) {
      if (Files.isRegularFile(path)) {
         val content = Files.readString(path)
         log.debug("Content of file $path is :\n$content")
      }
    }
```   

### Executing commands in a running container

The following example shows how to run a command in a running container and capture the output:

```kotlin
    val input = ByteArrayOutputStream()
    val (exitCode, stdErr) = container.execute(
       command = List.of("ls","-la", "/"),
       output = output)
    log.debug("Result of command:\n${output.toUtf8String}")
```

The following example shows how to run a command with input in a running container, and capture the output:

```kotlin
    val inputString = "Hello, world!"
    val input = inputString.byteInputStream()
    val output = ByteArrayOutputStream() // Output stream to capture the output - should be the same as the input
   
    val (exitCode, stderr) = container.execute(
       Executable.of("cat"),
       null,
       true,
       UnixDir.of(FORWARD_SLASH),
       input,
       output,
       20,
       TimeUnit.SECONDS
    )

    val stdout = output.toUtf8String()
```

## Requirements

### Docker
Developed using Docker [version 26.0](https://docs.docker.com/engine/release-notes/26.0/).

### Kubernetes
Developed using Kubernetes [version 1.29.2](https://kubernetes.io/releases/)  on Kind [version 0.22](https://github.com/kubernetes-sigs/kind/releases).

## Configuration
See the [test-env](https://github.com/acntech/easycontainers/tree/main/src/test/resources/env) folder for scripts and resources to set up a local environment for running the tests.

### Docker specifics

### Kubernetes specifics
In the current version, when running outside a k8s cluster, the Kubernetes runtime only supports using the default `kubeconfig` file located in the user's home directory. If using the library inside a Kubernetes cluster (i.e. in a pod/container), the Fabric8 default approach is used to authenticate the client, i.e. the mounted service account token at `/var/run/secrets/kubernetes.io/serviceaccount/token`. Make sure the service account has the necessary permissions to create and manage resources in the target namespace.

## Examples

### Docker examples
TODO

### Kubernetes examples
TODO

## API Reference
* [JavaDoc](https://blog.acntech.no/easycontainers/apidocs/javadoc/index.html)
* [KDoc](https://blog.acntech.no/easycontainers/apidocs/kdoc/index.html)

## Known Issues
- The Docker container runtime implementation does not support executing commands with stdin input due to a "hijacking session" issue that is not yet resolved. 
- More test cases are needed to ensure the library is robust and reliable for both Docker and Kubernetes container runtimes in production environments. 

## Troubleshooting
TODO

## FAQs
TODO

## Community and Support
For issues and bugs please submit an issue on the [GitHub repository](https://github.com/acntech/kollective-query/issues). Also feel free to contact the main contributor and maintainer directly at his [personal email](mailto:me.thomas.muller@gmail.com) or [work email](mailto:thomas.muller@accenture.com).

## Acknowledgments
TODO

## Roadmap
- [x] Add support for Kubernetes Jobs as a container runtime.
- [ ] Add conditional wait strategies for containers - similar to Testcontainers, see [here](https://java.testcontainers.org/features/startup_and_waits/).
- [ ] Add support for binary (frame-by-frame) stdout output from containers.
- [ ] Add specific container implementations for popular databases and services.
- [ ] Add support for multi-pod/multi-container deployments in both Docker and Kubernetes.
- [ ] Convert all tests to use [Testcontainers](https://testcontainers.com/) - using either the official [K3s module](https://java.testcontainers.org/modules/k3s/), or the community contributed [KinD module](https://testcontainers.com/modules/kindcontainer/).

## Contributing
[Thomas Muller](mailto:thomas.muller@accenture.com) ([personal email](mailto:me.thomas.muller@gmail.com)): main contributor and maintainer

## License
This software is licensed under the Apache 2 license, see [LICENSE](https://github.com/acntech/easycontainers/blob/main/LICENSE) and [NOTICE](https://github.com/acntech/easycontainers/blob/main/NOTICE) for details.