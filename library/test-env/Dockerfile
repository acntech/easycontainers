# Use Alpine Linux as the base image
FROM alpine:latest

# Install curl, httpie, openssh, Python, and other necessary dependencies
RUN apk add --no-cache curl netcat-openbsd openssh

# Additional setup SSH
RUN sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config \
    && sed -i 's/#PasswordAuthentication yes/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && echo "root:root" | chpasswd \
    && ssh-keygen -A

# Copy the log-time.sh script to the container
COPY log-time.sh /usr/local/bin/log-time.sh

# Make the log-time.sh script executable
RUN chmod +x /usr/local/bin/log-time.sh

# Create a startup script directly in the Dockerfile
RUN echo '#!/bin/sh' > /start.sh \
    && echo '/usr/local/bin/log-time.sh &' >> /start.sh \
    && echo '/usr/sbin/sshd -D' >> /start.sh \
    && chmod +x /start.sh

# Expose necessary ports (e.g., SSH port)
EXPOSE 22

# Define your container's default behavior
CMD ["/start.sh"]
