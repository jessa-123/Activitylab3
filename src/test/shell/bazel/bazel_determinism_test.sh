#!/bin/bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Test that bootstrapping bazel is a fixed point
#

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
set -euo pipefail
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if "$is_windows"; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

declare -r DISTFILE=$(rlocation io_bazel/${1#./})

# sha1sum is easily twice as fast as shasum, but not always available on macOS.
if hash sha1sum 2>/dev/null; then
  shasum="sha1sum"
elif hash shasum 2>/dev/null; then
  shasum="shasum"
else
  fail "Could not find sha1sum or shasum on the PATH"
fi

function hash_outputs() {
  # runfiles/MANIFEST & runfiles_manifest contain absolute path, ignore.
  # ar on OS-X is non-deterministic, ignore .a files.
  find bazel-bin/ bazel-genfiles/ \
      -type f \
      -a \! -name MANIFEST \
      -a \! -name '*.runfiles_manifest' \
      -a \! -name '*.a' \
      -exec $shasum {} + \
      | awk '{print $2, $1}' \
      | sort \
      | sed "s://:/:"
}

function test_determinism()  {
    local workdir="${TEST_TMPDIR}/workdir"
    mkdir "${workdir}" || fail "Could not create work directory"
    cd "${workdir}" || fail "Could not change to work directory"
    unzip -q "${DISTFILE}"

    # Build Bazel once.
    bazel --output_base="${TEST_TMPDIR}/out1" build --nostamp //src:bazel
    hash_outputs >"${TEST_TMPDIR}/sum1"

    # Build Bazel twice.
    bazel-bin/src/bazel --output_base="${TEST_TMPDIR}/out2" build --nostamp //src:bazel
    hash_outputs >"${TEST_TMPDIR}/sum2"

    if ! diff -U0 "${TEST_TMPDIR}/sum1" "${TEST_TMPDIR}/sum2" >$TEST_log; then
      fail "Non-deterministic outputs found!"
    fi
}

run_suite "determinism test"
