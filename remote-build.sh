#!/bin/bash
# Sync source and build on nllei01androidsdk01 (192.168.181.101)
# Usage: ./remote-build.sh [assembleFdroidDebug|assemblePlayDebug|testFdroidDebugUnitTest|clean|...]
# Two flavors since 2.19.0 (MESHSAT-1335): fdroid (full, default) and play (no SMS).
set -e
VM=ansible@192.168.181.101
REMOTE_DIR=/home/ansible/meshsat-android
TARGET="${1:-assembleFdroidDebug}"

# Sync source
tar czf - --exclude='.gradle' --exclude='build' --exclude='.claude' --exclude='*.apk' --exclude='.git' . | \
  ssh "$VM" "mkdir -p $REMOTE_DIR && cd $REMOTE_DIR && tar xzf -"

# Build
ssh "$VM" "cd $REMOTE_DIR && export ANDROID_HOME=/opt/android-sdk && export GRADLE_USER_HOME=/home/ansible/.gradle && ./gradlew --no-daemon --offline $TARGET"

# Copy the debug APK(s) back after an assemble; gradle names them
# meshsat-android-<version>[-play]-universal-<code>.apk under apk/<flavor>/debug/.
if [[ "$TARGET" == *"assemble"* ]]; then
  for APK in $(ssh "$VM" "ls $REMOTE_DIR/app/build/outputs/apk/*/debug/*.apk 2>/dev/null"); do
    REL="${APK#$REMOTE_DIR/}"
    mkdir -p "$(dirname "$REL")"
    scp -q "$VM:$APK" "$REL" && echo "copied $REL"
  done
fi
