#!/bin/bash
# The double slashes are needed to avoid mingw path conversions. 
# Seems it also works in Linux

# The host that the ldb server should use to contact the scala executor. 
# On a Linux machine, this can be localhost. But when using boot2docker, the localhost that the container sees
# is the virtual interface created by VirtualBox. So we have to specify the external facing IP address of the host.
if [[ $(uname -s) == CYGWIN* || $(uname -s) == MINGW* ]]; then
	# WARN: This script takes the IP of the first interface listed by ifconfig. It may not be the external IP
	SCALA_SERVER_HOST=$(ipconfig | grep "IPv4 Address" | head -n 1 | cut -c 40-55)
else
	SCALA_SERVER_HOST=localhost
fi

echo "Using ${SCALA_SERVER_HOST} for Scala executor"
# Entrypoint has a funny syntax when running with arguments. A command like "xyz -a -b foobar",
# must be specified in this format: --entrypoint=xyz <dockerImage> -a -b foobar. 
# https://docs.docker.com/reference/run/#entrypoint-default-command-to-execute-at-runtime
docker run -it --rm -p 5000:5000 --entrypoint=//raw/scripts/run-with-scala-backend.sh raw/ldb ${SCALA_SERVER_HOST}
