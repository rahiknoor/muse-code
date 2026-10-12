#!/bin/bash
# Recompiles the shared DCF engine classes in ~/workspace/dcf_csv/.
# Run this after editing DcfModel.java or ForwardComplianceGate.java,
# before running the rebuild or the gate.
# The forward-tilt compliance gate runs as a compiled class (not single-file
# source launch) because it needs package-private access to DcfModel.Json,
# which breaks across the single-file launcher's classloader.
# Tools use them via:
#   java --class-path ~/workspace/dcf_csv/classes:~/workspace/java-shared/classes \
#        ForwardComplianceGate --check
export JAVA_HOME=~/workspace/java/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
set -e
mkdir -p ~/workspace/dcf_csv/classes
javac -cp ~/workspace/java-shared/classes -d ~/workspace/dcf_csv/classes \
    ~/workspace/dcf_csv/DcfModel.java \
    ~/workspace/dcf_csv/ForwardComplianceGate.java
echo "dcf classes rebuilt."
