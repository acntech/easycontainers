# Easycontainers

## Table of Contents

1. [Introduction](#Introduction)
2. [Example applications](#Example-applications)
3. [TODO](#TO-DO)
4. [Contributing](#contributing)
5. [License](#license)

## Introduction

Easycontainers is a Kotlin library that allows you to easily create and manage containers in Kubernetes and Docker.
[build.bat](..%2F..%2F..%2FDocker%2FRegistry%2Fbuild.bat)
## Architecture

## Example-applications

See the [examples](examples) folder for examples on how to use the library.

## TO-DO

* Add Docker container support (TODO)
* Employ Kaniko pod (k8s job) in order to build (custom) docker files and push to a registry
* Create specific container implementations for well-known services, e.g. Elasticsearch, Kafka, Redis, etc.

## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

## License
2024, Accenture Inc. All Rights Reserved[build.bat](..%2F..%2F..%2FDocker%2FRegistry%2Fbuild.bat).   

This software is provided "as is" without warranty of any kind, either expressed or implied, including, but not limited to, the implied warranties of merchantability and fitness for a particular purpose. In no event shall Accenture Inc or its contributors be liable for any direct, indirect, incidental, s[pom.xml](examples/spring-boot/pom.xml)pecial, exemplary, or consequential damages (including, but not limited to, procurement of substitute goods or services; loss of use,[pom.xml](examples/spring-boot/pom.xml) data, or profits; or business interruption) however caused and on any theory of liability, whether in contract, strict liability, or tort (including negligence or otherwise) arising.