FROM amazoncorretto:21-alpine

RUN apk update && apk upgrade -U
RUN apk add --no-cache curl
RUN apk add --no-cache jq
RUN apk add --no-cache netcat-openbsd
RUN apk add --no-cache bash

# Create directories
RUN mkdir -p /opt/app/log

# Set permissions
RUN chgrp -R 0 /opt/app && \
    chmod -R g+rwX /opt/app

WORKDIR /opt/app

# Copy necessary files
ADD target/*.jar app.jar
ADD start.sh start.sh
ADD jvm_options jvm_options

# Make the start script executable
RUN chmod +x /opt/app/start.sh

# Ensure jvm_options is readable by the root group
RUN chgrp 0 /opt/app/jvm_options && \
    chmod g+r /opt/app/jvm_options

ENTRYPOINT ["/bin/sh","/opt/app/start.sh"]

