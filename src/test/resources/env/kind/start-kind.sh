#!/bin/bash

kind delete cluster --name test
./prepare-kind-config.sh
kind create cluster --name test --config kind-config.yaml
