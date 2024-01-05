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

kubectl apply -f service-account.yaml
kubectl apply -f role.yaml
kubectl apply -f role-binding.yaml

./run-registry.sh

docker build -t localhost:5000/alpine-simple-http:latest .
docker push localhost:5000/alpine-simple-http:latest
curl http://localhost:5000/v2/_catalog



