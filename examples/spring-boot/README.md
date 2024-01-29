# Easycontainers

## Table of Contents

1. [Hov to run](#How-to-run)
2. [Example application](#Example-application)
3. [TO-DO](#TO-DO)
4. [Contributing](#contributing)

## How-to-run
Prerequisites:
- Docker - the simplest way to install it is by using Docker Desktop
- Kubernetes - the simplest way to install it is by using the K8s distribution offered by Docker Desktop

1. Make sure that Docker and Kubernetes are available and running. 

Docker test:
```bash
> docker ps
```

Kubernetes test:
```bash
> kubectl get pods
```

2. Install the test environment by running the following command:

```bash
> cd test-env
> ./test-env/create-env.sh
```

This is a shell script designed to automate the setup process for a Kubernetes test environment.
* Checks for or creates a Kubernetes namespace named 'test'.
* Applies Kubernetes resources defined in the YAML files: service-account.yaml, role.yaml, role-binding.yaml, Kaniko-pv.yaml, and kanilo-pvc.yaml.
* Executes the run-registry.sh script to start a local Docker registry.
* Builds a Docker image and pushes it to the local Docker registry.
* Lists all the images in the local Docker registry.

3. Deploy the test application to your local k8s cluster by running the following commands on the top level folder:
```bash
> mvn clean package
> mv examples/spring-boot
> ./deploy-app.sh
```

You can check the status of the deployment by using e.g. Lens or kubectl:
```bash
> kubectl get pods
```

Also - check the logs from the application using Lens or kubectl:
```bash
> kubectl logs -f <pod-name>
```

4. Delete the test application by running the following command:
```bash
> ./delete-app.sh
```

## Example-application

If  the application runs OK inside your k8s cluster, it should be exposed on port 30080 on the node (normally localhost), and you should be able to connect with a browser to ```http://localhost:30080/hello``` for a 'hello world' response, and  
```http://localhost:30080/connect``` to get the index page of the (light) httpd server the application has deployed in k8s.

## TO-DO

* Add examples for Docker container management (when the Docker container support is added) 

## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

