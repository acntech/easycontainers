# Easycontainers

## Table of Contents

1. [Hov to run](#How-to-run)
2. [Example application](#Example-application)
3. [TO-DO](#TO-DO)
4. [Contributing](#contributing)

## How-to-run

Prerequisites:
- Docker - the simplest way to install it is by using Docker Desktop
- Kubernetes - the simplest way to install it is by using the K8s distribution of Docker Desktop

Makes sure that Docker and Kubernetes are available and running. 

Docker test:
```> docker ps```

Kubernetes test:
```> kubectl get pods```

Install the test environment by running the following command:
```> ./test-env/create-env.sh```

Deploy the test application by running the following command:
```> ./deploy-app.sh```
[AppConfig.kt](src%2Fmain%2Fkotlin%2Fno%2Facntech%2Feasycontainers%2Fspring_boot_example%2Fconfig%2FAppConfig.kt)
Delete the test application by running the following command:
```> ./delete-app.sh```

## TO-DO

## Contributing
- Thomas Muller (thomas.muller@accenture.com): main contributor and maintainer

