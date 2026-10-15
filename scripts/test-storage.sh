#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${MULTISENSE_JSON_JAR:-}" ]; then
  if [ -f "json-20240303.jar" ]; then
    MULTISENSE_JSON_JAR="json-20240303.jar"
  else
    echo "Set MULTISENSE_JSON_JAR to org.json:json:20240303.jar" >&2
    exit 1
  fi
fi
mkdir -p .build/storage-test
case "$(uname -s)" in
  *MINGW*|*MSYS*|*CYGWIN*) CP_SEP=";" ;;
  *) CP_SEP=":" ;;
esac
javac -cp "$MULTISENSE_JSON_JAR" -d .build/storage-test tests/stubs/android/os/Build.java app/src/main/java/edu/multisense/collector/Csv.java app/src/main/java/edu/multisense/collector/SampleStats.java app/src/main/java/edu/multisense/collector/SamplingPolicy.java app/src/main/java/edu/multisense/collector/SessionRecorder.java tests/StorageTest.java
java -cp ".build/storage-test${CP_SEP}$MULTISENSE_JSON_JAR" edu.multisense.collector.StorageTest
