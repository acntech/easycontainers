#!/bin/bash

kind delete cluster --name test
kind create cluster --name test --config kind-config.yaml
