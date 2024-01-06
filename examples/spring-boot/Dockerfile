# ---
# This Dockerfile is used to build an image containing the Spring Boot example application.
# ---

FROM amazoncorretto:21-alpine

# Install necessary packages
RUN apk add --no-cache curl jq netcat-openbsd bash

# Create a group and user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Create directories and set permissions
RUN mkdir -p /opt/app/log && \
    chgrp -R 0 /opt/app && \
    chmod -R g+rwX /opt/app

WORKDIR /opt/app

# Copy necessary files
COPY target/*.jar app.jar
COPY start.sh start.sh
COPY jvm_options jvm_options

# Make the start script executable and ensure jvm_options is readable
RUN chmod +x start.sh && \
    chgrp 0 jvm_options && \
    chmod g+r jvm_options && \
    chown -R appuser:appgroup /opt/app

# Change to the app user
USER appuser

ENTRYPOINT ["/bin/sh", "start.sh"]
