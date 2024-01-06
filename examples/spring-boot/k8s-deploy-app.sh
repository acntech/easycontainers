#!/bin/bash

# Variables
DOCKER_REGISTRY="localhost:5000"
IMAGE_NAME="easycontainers-spring-boot-test-app"
IMAGE_TAG="latest"
DOCKERFILE_PATH="./Dockerfile"
K8S_MANIFEST_PATH="./k8s-manifest.yaml"

function handleError {
    echo "Error on line $1"
    echo "Deployment failed"
    exit 1
}

./k8s-delete-app.sh

# Trap any error, and call handleError function with the line number
trap 'handleError $LINENO' ERR

# Step 1: Maven Package
# echo "Building..."
# mvn package

# Step 2: Build Docker Image
echo "Building Docker Image..."
docker build -t ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} -f ${DOCKERFILE_PATH} .

# Step 3: Push to Local Registry
echo "Pushing to local Docker registry..."
docker push ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}

# Step 4: Apply Kubernetes Manifest
echo "Applying Kubernetes manifest."
kubectl apply -f ${K8S_MANIFEST_PATH}

echo "Deployment completed successfully."
