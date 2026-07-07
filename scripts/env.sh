#!/usr/bin/env bash

if [[ -d /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ]]; then
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  for sdk in "$HOME/Library/Android/sdk" "$HOME/Documents/AndroidSDK"; do
    if [[ -d "$sdk/platforms" && -d "$sdk/build-tools" ]]; then
      export ANDROID_HOME="$sdk"
      export ANDROID_SDK_ROOT="$sdk"
      break
    fi
  done
fi
