#!/bin/bash

kind delete cluster
kind create cluster --name kind --config kind-config.yaml
