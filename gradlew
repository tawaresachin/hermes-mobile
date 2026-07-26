#!/data/data/com.termux/files/usr/bin/bash

# Gradle wrapper script for Hermes Mobile
export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Determine the project root
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Run Gradle with the wrapper jar
exec java -Xmx2048m \
    -Dfile.encoding=UTF-8 \
    -Dorg.gradle.appname=gradlew \
    -classpath "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
