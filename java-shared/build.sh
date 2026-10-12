#!/bin/bash
# Recompiles the shared Java helpers in ~/workspace/java-shared/.
# Run this after editing any *.java here, before running tools that use them.
# Tools use them via: java --class-path ~/workspace/java-shared/classes <Tool>.java ...
export JAVA_HOME=~/workspace/java/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
set -e
mkdir -p ~/workspace/java-shared/classes
javac -d ~/workspace/java-shared/classes ~/workspace/java-shared/*.java
echo "shared classes rebuilt: $(ls ~/workspace/java-shared/classes | tr '\n' ' ')"
