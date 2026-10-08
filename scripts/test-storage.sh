#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${MULTISENSE_JSON_JAR:?Set MULTISENSE_JSON_JAR to org.json:json:20240303.jar}"
mkdir -p .build/storage-test
javac -cp "$MULTISENSE_JSON_JAR" -d .build/storage-test tests/stubs/android/os/Build.java app/src/main/java/edu/multisense/collector/Csv.java app/src/main/java/edu/multisense/collector/SampleStats.java app/src/main/java/edu/multisense/collector/SessionRecorder.java tests/StorageTest.java
java -cp ".build/storage-test:$MULTISENSE_JSON_JAR" edu.multisense.collector.StorageTest
