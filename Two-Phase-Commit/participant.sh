#!/bin/bash +vx
LIB_PATH=$"lib/libthrift-0.9.2.jar:lib/slf4j-simple-1.7.12.jar:lib/slf4j-api-1.7.12.jar"
#port
java -classpath bin:$LIB_PATH ServerMain $1 $2
