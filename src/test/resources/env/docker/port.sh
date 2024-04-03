#!/bin/bash

DOCKER_HOST_IP=$(ifconfig eth0 | grep 'inet ' | awk '{print $2}')

docker kill portainer
docker rm portainer
docker run -d -p 8000:8000 -p 9443:9443 --name portainer --restart=always -v portainer_data:/data portainer/portainer-ce:latest -H tcp://$DOCKER_HOST_IP:2375