#!/bin/bash

#!/bin/bash

# Set Kubernetes namespace
NAMESPACE="test"

# Resource names
DEPLOYMENT_NAME="easycontainers-spring-boot-test-app-deployment"
SERVICE_NAME="easycontainers-spring-boot-test-app-service"

# Delete Deployment
echo "Deleting Deployment: $DEPLOYMENT_NAME"
kubectl delete deployment $DEPLOYMENT_NAME -n $NAMESPACE

# Delete Service
echo "Deleting Service: $SERVICE_NAME"
kubectl delete service $SERVICE_NAME -n $NAMESPACE

echo "Deletion complete."

