#!/bin/bash

NAMESPACE="test"
REPOSITORY="test"

if [ -z "$DOCKER_HOST" ]; then
    REGISTRY_IP="localhost"
else
    DOCKER_HOST=${DOCKER_HOST/tcp:\/\//}

    # Split the DOCKER_HOST variable into an array based on ':'
    IFS=':' read -ra ADDR <<< "$DOCKER_HOST"

    # Extract the IP
    REGISTRY_IP=${ADDR[0]}
fi

# Use a constant port for the registry
REGISTRY_PORT=5000

REGISTRY_HOST="$REGISTRY_IP:$REGISTRY_PORT"

echo "Using registry host: $REGISTRY_HOST"

# Check if the namespace already exists
if kubectl get namespace "$NAMESPACE" > /dev/null 2>&1; then
    echo "Namespace '$NAMESPACE' already exists"
else
    # Create the namespace
    kubectl create namespace "$NAMESPACE"
    if [ $? -eq 0 ]; then
        echo "Namespace '$NAMESPACE' created successfully"
    else
        echo "Failed to create namespace '$NAMESPACE'"
    fi
fi

# Create the service account, role and role binding
kubectl apply -f service-account.yaml
kubectl apply -f role.yaml
kubectl apply -f role-binding.yaml

# Create the Kaniko persistent volume and persistent volume claim
kubectl apply -f kaniko-pv-docker-wsl.yaml
kubectl apply -f kaniko-pvc.yaml

# Create the generic host share persistent volume and persistent volume claim
kubectl apply -f host-share-pv-docker-wsl.yaml
kubectl apply -f host-share-pvc.yaml

# Start a local registry in Docker
# ./run-registry.sh

export DOCKER_CLI_AWS_NO_SIGN_REQUEST=1

# Build and push the light-httpd image
#docker build -t "$REGISTRY_HOST/$REPOSITORY/alpine-simple-ssh:latest" .
#docker push "$REGISTRY_HOST/$REPOSITORY/alpine-simple-ssh:latest"

# List the images in the registry
echo "Images in the registry:"
curl "http://$REGISTRY_HOST/v2/_catalog"



