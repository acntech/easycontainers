#!/bin/bash

docker kill registry
docker rm registry
docker run -d -p 5000:5000 --restart=always --name registry registry:latest
