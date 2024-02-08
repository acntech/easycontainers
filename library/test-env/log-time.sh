#!/bin/sh

count=1
while true
do
  echo "${count}: $(date"
  count=$((count+1))
  sleep 2
done