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

Makes sure that Docker and Kubernetes are available and running. 

Docker test:
```bash
> docker ps
```

Kubernetes test:
```
> kubectl get pods
```

Install the test environment by running the following command:

```bash
> cd test-env
> ./test-env/create-env.sh
```

Deploy the test application by running the following commands:
```bash
> mvn clean package
> ./deploy-app.sh
```

You can check the status of the deployment by using e.g. Lens or kubectl:
```bash
> kubectl get pods
```

If  the application runs OK, it should be exposed on port 30080, you should be able to connect with a browser to ```http://localhost:30080/hello```, and  
```http://localhost:30080/connect``` to get the index page of the httpd server the application has deployed inside k8s.

Also - check the logs from the application using Lens or kubectl:
```bash
> kubectl logs -f <pod-name>
```

Delete the test application by running the following command:
```bash
> ./delete-app.sh
```

## TO-DO

## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

