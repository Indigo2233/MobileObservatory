#!/usr/bin/env bash
# Applies patches/libusb-android-fd.patch to the libusb submodule when needed.
# Idempotent: skips if libusb_set_android_fd is already present.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBUSB_DIR="$ROOT/app/src/main/cpp/libusb"
TARGET="$LIBUSB_DIR/libusb/os/linux_usbfs.c"
PATCH="$ROOT/patches/libusb-android-fd.patch"

if [[ ! -f "$TARGET" ]]; then
  echo "libusb source missing: $TARGET (run git submodule update --init)" >&2
  exit 1
fi
if [[ ! -f "$PATCH" ]]; then
  echo "libusb Android FD patch missing: $PATCH" >&2
  exit 1
fi

if grep -q "libusb_set_android_fd" "$TARGET"; then
  echo "libusb Android FD patch already applied."
  exit 0
fi

echo "Applying libusb Android FD patch..."
git -C "$LIBUSB_DIR" apply --whitespace=nowarn "$PATCH"

if ! grep -q "libusb_set_android_fd" "$TARGET"; then
  echo "Patch applied but libusb_set_android_fd was not found in $TARGET" >&2
  exit 1
fi

echo "libusb Android FD patch applied."
