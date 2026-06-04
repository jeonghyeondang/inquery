#!/bin/bash

# Required JVM options for the Snowflake JDBC driver
# Lets Apache Arrow access java.nio.Buffer internals under Java 17+ module system
JVM_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED"

# Start server
cd "$(dirname "$0")"
java $JVM_OPTS -jar target/inquery-server-web-start.jar

