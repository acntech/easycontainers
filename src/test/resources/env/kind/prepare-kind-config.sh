#!/bin/bash

# Define the path to the Kind config template
CONFIG_TEMPLATE="kind-config-template.yaml"
CONFIG_OUTPUT="kind-config.yaml"
KANIKO_DATA_SHARE="/home/thomas/kind/kaniko-data"
GLOBAL_DATA_SHARE="/home/thomas/kind/share"


# Use ifconfig to get the IP address of the primary network interface (e.g., eth0)
IP_ADDRESS=$(ifconfig eth0 | grep 'inet ' | awk '{ print $2 }' | head -n 1)

# Check if we successfully obtained an IP address
if [ -z "$IP_ADDRESS" ]; then
    echo "Failed to obtain IP address from ifconfig"
    exit 1
fi

echo "Using IP address: $IP_ADDRESS"

# Use sed to replace the placeholder with the actual IP address in the config file
sed -e "s/\${ip-address}/$IP_ADDRESS/g" \
    -e "s/\${kaniko-data}/$KANIKO_DATA_SHARE/g" \
    -e "s/\${general-data}/$GLOBAL_DATA_SHARE/g" \
    "$CONFIG_TEMPLATE" > "$CONFIG_OUTPUT"

echo "Config file has been prepared and saved to: $CONFIG_OUTPUT"
