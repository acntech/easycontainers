# Simple lighttpd container, with a script that logs the time every 2 seconds to std out

# Use Alpine Linux as the base image
FROM alpine:latest

# Install lighttpd
RUN apk update && apk add lighttpd

# Create a directory for the web content
RUN mkdir -p /var/www/localhost/htdocs

# Create an index.html file with the message
RUN echo "<html><body><h1>Hello from httpdlight</h1></body></html>" > /var/www/localhost/htdocs/index.html

# Copy the log_time.sh script to the container
COPY log-time.sh /usr/local/bin/log-time.sh

# Make the script executable
RUN chmod +x /usr/local/bin/log-time.sh

# Expose port 80
EXPOSE 80

# Start both lighttpd and the time-logging script, capturing its output to stdout
CMD sh -c "lighttpd -D -f /etc/lighttpd/lighttpd.conf -D www-data & /usr/local/bin/log-time.sh"
