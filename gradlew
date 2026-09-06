#!/bin/sh
# Gradle wrapper script
export GRADLE_USER_HOME="${HOME}/.gradle"
exec gradle "$@"
