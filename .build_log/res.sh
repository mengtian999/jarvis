#!/bin/bash
set -e
export JAVA_HOME="$(cygpath -u 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot')"
export PATH="$JAVA_HOME/bin:$PATH"
cd "D:/Code/IM/OpenMinis/OpenMinis-main/src/android" || exit 1
./gradlew :app:mergeDebugResources --console=plain --no-daemon --stacktrace --info 2>&1