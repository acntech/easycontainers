#!/bin/bash

NAMESPACE_NAME="test"

# Check if the namespace already exists
if kubectl get namespace "$NAMESPACE_NAME" > /dev/null 2>&1; then
    echo "Namespace '$NAMESPACE_NAME' already exists"
else
    # Create the namespace
    kubectl create namespace "$NAMESPACE_NAME"
    if [ $? -eq 0 ]; then
        echo "Namespace '$NAMESPACE_NAME' created successfully"
    else
        echo "Failed to create namespace '$NAMESPACE_NAME'"
    fi
fi

# Create the service account, role and role binding
kubectl apply -f service-account.yaml
kubectl apply -f role.yaml
kubectl apply -f role-binding.yaml
kubectl apply -f Kaniko-pv.yaml
kubectl apply -f kanilo-pvc.yaml

# Start a local registry
./run-registry.sh

# Build and push the light-httpd image
docker build -t localhost:5000/alpine-simple-httpd:latest .
docker push localhost:5000/alpine-simple-httpd:latest

# List the images in the registry
curl http://localhost:5000/v2/_catalog



