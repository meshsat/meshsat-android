#!/bin/bash
# Sync source and build on nllei01androidsdk01 (192.168.181.101)
# Usage: ./remote-build.sh [assembleDebug|testDebugUnitTest|clean|...]
set -e
VM=ansible@192.168.181.101
REMOTE_DIR=/home/ansible/meshsat-android
TARGET="${1:-assembleDebug}"

# Sync source
tar czf - --exclude='.gradle' --exclude='build' --exclude='.claude' --exclude='*.apk' --exclude='.git' . | \
  ssh "$VM" "mkdir -p $REMOTE_DIR && cd $REMOTE_DIR && tar xzf -"

# Build
ssh "$VM" "cd $REMOTE_DIR && export ANDROID_HOME=/opt/android-sdk && export GRADLE_USER_HOME=/home/ansible/.gradle && ./gradlew --no-daemon --offline $TARGET"

# Copy APK back if assembleDebug
if [[ "$TARGET" == *"assemble"* ]]; then
  ssh "$VM" "ls $REMOTE_DIR/app/build/outputs/apk/debug/app-debug.apk" >/dev/null 2>&1 && \
    scp "$VM:$REMOTE_DIR/app/build/outputs/apk/debug/app-debug.apk" app/build/outputs/apk/debug/app-debug.apk 2>/dev/null
fi
