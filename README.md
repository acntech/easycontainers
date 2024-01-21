# Easycontainers

## Table of Contents

1. [Introduction](#Introduction)
2. [Example applications](#Example-applications)
3. [TODO](#TO-DO)
4. [Contributing](#contributing)
5. [License](#license)

## Introduction
 
Easycontainers is a Kotlin library that allows you to easily create and manage (generic) containers in Kubernetes and Docker. The inspiration for this library came from the [testcontainers](https://www.testcontainers.org/) library, which is a Java library that allows you to easily create and manage containers for testing purposes. However, the testcontainers library is not suitable for production use, as it is not possible to create containers in Kubernetes. This library aims to provide a similar experience as the testcontainers library, but with the added benefit of being able to create containers in Kubernetes.

## Example-applications

See the [examples](examples) folder for examples on how to use the library.

## TO-DO

* Create specific container implementations for well-known services, e.g. Elasticsearch, Kafka, Redis, etc.
* Add Docker container support
* Employ Kaniko pod (external k8s job) in order to programmatically build (custom) docker files and push to a registry
* Use JIB to build and push custom images to a registry

## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

## License
2024, Accenture Inc. All Rights Reserved

This software is provided "as is" without warranty of any kind, either expressed or implied, including, but not limited to, the implied warranties of merchantability and fitness for a particular purpose. In no event shall Accenture Inc or its contributors be liable for any direct, indirect, incidental, special, exemplary, or consequential damages (including, but not limited to, procurement of substitute goods or services; loss of use, data, or profits; or business interruption) however caused and on any theory of liability, whether in contract, strict liability, or tort (including negligence or otherwise) arising.