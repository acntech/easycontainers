#!/bin/sh

echo "Env -->"
printenv
printf "\n"

echo "----> JVM options"
cat /opt/app/jvm_options
printf "\n\n"

echo "----> Java app"
java @jvm_options