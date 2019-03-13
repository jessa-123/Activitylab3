#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
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

set -eu

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# Asserts if the given expected coverage result is included in the given output
# file.
#
# - expected_coverage The expected result that must be included in the output.
# - output_file       The location of the coverage output file.
function assert_coverage_result() {
    local expected_coverage="${1}"; shift
    local output_file="${1}"; shift

    # Replace newlines with commas to facilitate the assertion.
    local expected_coverage_no_newlines="$( echo "$expected_coverage" | tr '\n' ',' )"
    local output_file_no_newlines="$( cat "$output_file" | tr '\n' ',' )"

    (echo "$output_file_no_newlines" \
        | grep -F "$expected_coverage_no_newlines") \
        || fail "Expected coverage result
<$expected_coverage>
was not found in actual coverage report:
<$( cat "$output_file" )>"
}

# Returns the path of the code coverage report that was generated by Bazel by
# looking at the current $TEST_log. The method fails if TEST_log does not
# contain any coverage report for a passed test.
function get_coverage_file_path_from_test_log() {
  local ending_part="$(sed -n -e '/PASSED/,$p' "$TEST_log")"

  local coverage_file_path=$(grep -Eo "/[/a-zA-Z0-9\.\_\-]+\.dat$" <<< "$ending_part")
  [[ -e "$coverage_file_path" ]] || fail "Coverage output file does not exist!"
  echo "$coverage_file_path"
}

function test_java_test_coverage() {
  cat <<EOF > BUILD
java_test(
    name = "test",
    srcs = glob(["src/test/**/*.java"]),
    test_class = "com.example.TestCollatz",
    deps = [":collatz-lib"],
)

java_library(
    name = "collatz-lib",
    srcs = glob(["src/main/**/*.java"]),
)
EOF

  mkdir -p src/main/com/example
  cat <<EOF > src/main/com/example/Collatz.java
package com.example;

public class Collatz {

  public static int getCollatzFinal(int n) {
    if (n == 1) {
      return 1;
    }
    if (n % 2 == 0) {
      return getCollatzFinal(n / 2);
    } else {
      return getCollatzFinal(n * 3 + 1);
    }
  }

}
EOF

  mkdir -p src/test/com/example
  cat <<EOF > src/test/com/example/TestCollatz.java
package com.example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestCollatz {

  @Test
  public void testGetCollatzFinal() {
    assertEquals(Collatz.getCollatzFinal(1), 1);
    assertEquals(Collatz.getCollatzFinal(5), 1);
    assertEquals(Collatz.getCollatzFinal(10), 1);
    assertEquals(Collatz.getCollatzFinal(21), 1);
  }

}
EOF

  bazel coverage --test_output=all //:test &>$TEST_log || fail "Coverage for //:test failed"
  cat $TEST_log
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"

  cat <<EOF > result.dat
SF:src/main/com/example/Collatz.java
FN:3,com/example/Collatz::<init> ()V
FN:6,com/example/Collatz::getCollatzFinal (I)I
FNDA:0,com/example/Collatz::<init> ()V
FNDA:1,com/example/Collatz::getCollatzFinal (I)I
FNF:2
FNH:1
BA:6,2
BA:9,2
BRF:2
BRH:2
DA:3,0
DA:6,3
DA:7,2
DA:9,4
DA:10,5
DA:12,7
LH:5
LF:6
end_of_record
EOF

  diff result.dat "$coverage_file_path" >> $TEST_log
  if ! cmp result.dat $coverage_file_path; then
    fail "Coverage output file is different with expected"
  fi
}

function test_java_test_coverage_combined_report() {

  cat <<EOF > BUILD
java_test(
    name = "test",
    srcs = glob(["src/test/**/*.java"]),
    test_class = "com.example.TestCollatz",
    deps = [":collatz-lib"],
)

java_library(
    name = "collatz-lib",
    srcs = glob(["src/main/**/*.java"]),
)
EOF

  mkdir -p src/main/com/example
  cat <<EOF > src/main/com/example/Collatz.java
package com.example;

public class Collatz {

  public static int getCollatzFinal(int n) {
    if (n == 1) {
      return 1;
    }
    if (n % 2 == 0) {
      return getCollatzFinal(n / 2);
    } else {
      return getCollatzFinal(n * 3 + 1);
    }
  }

}
EOF

  mkdir -p src/test/com/example
  cat <<EOF > src/test/com/example/TestCollatz.java
package com.example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestCollatz {

  @Test
  public void testGetCollatzFinal() {
    assertEquals(Collatz.getCollatzFinal(1), 1);
    assertEquals(Collatz.getCollatzFinal(5), 1);
    assertEquals(Collatz.getCollatzFinal(10), 1);
    assertEquals(Collatz.getCollatzFinal(21), 1);
  }

}
EOF

  bazel coverage --test_output=all //:test --coverage_report_generator=@bazel_tools//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:Main --combined_report=lcov &>$TEST_log \
   || echo "Coverage for //:test failed"

  cat <<EOF > result.dat
SF:src/main/com/example/Collatz.java
FN:3,com/example/Collatz::<init> ()V
FN:6,com/example/Collatz::getCollatzFinal (I)I
FNDA:0,com/example/Collatz::<init> ()V
FNDA:1,com/example/Collatz::getCollatzFinal (I)I
FNF:2
FNH:1
BA:6,2
BA:9,2
BRF:2
BRH:2
DA:3,0
DA:6,3
DA:7,2
DA:9,4
DA:10,5
DA:12,7
LH:5
LF:6
end_of_record
EOF

  if ! cmp result.dat ./bazel-out/_coverage/_coverage_report.dat; then
    diff result.dat bazel-out/_coverage/_coverage_report.dat >> $TEST_log
    fail "Coverage output file is different with expected"
  fi
}

function test_java_test_java_import_coverage() {

  cat <<EOF > BUILD
java_test(
    name = "test",
    srcs = glob(["src/test/**/*.java"]),
    test_class = "com.example.TestCollatz",
    deps = [":collatz-import"],
)

java_import(
    name = "collatz-import",
    jars = [":libcollatz-lib.jar"],
)

java_library(
    name = "collatz-lib",
    srcs = glob(["src/main/**/*.java"]),
)
EOF

  mkdir -p src/main/com/example
  cat <<EOF > src/main/com/example/Collatz.java
package com.example;

public class Collatz {

  public static int getCollatzFinal(int n) {
    if (n == 1) {
      return 1;
    }
    if (n % 2 == 0) {
      return getCollatzFinal(n / 2);
    } else {
      return getCollatzFinal(n * 3 + 1);
    }
  }

}
EOF

  mkdir -p src/test/com/example
  cat <<EOF > src/test/com/example/TestCollatz.java
package com.example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestCollatz {

  @Test
  public void testGetCollatzFinal() {
    assertEquals(Collatz.getCollatzFinal(1), 1);
    assertEquals(Collatz.getCollatzFinal(5), 1);
    assertEquals(Collatz.getCollatzFinal(10), 1);
    assertEquals(Collatz.getCollatzFinal(21), 1);
  }

}
EOF

  bazel coverage --test_output=all --experimental_java_coverage //:test &>$TEST_log || fail "Coverage for //:test failed"
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"

  cat <<EOF > result.dat
SF:src/main/com/example/Collatz.java
FN:3,com/example/Collatz::<init> ()V
FN:6,com/example/Collatz::getCollatzFinal (I)I
FNDA:0,com/example/Collatz::<init> ()V
FNDA:1,com/example/Collatz::getCollatzFinal (I)I
FNF:2
FNH:1
BA:6,2
BA:9,2
BRF:2
BRH:2
DA:3,0
DA:6,3
DA:7,2
DA:9,4
DA:10,5
DA:12,7
LH:5
LF:6
end_of_record
EOF
  diff result.dat "$coverage_file_path" >> $TEST_log
  cmp result.dat "$coverage_file_path" || fail "Coverage output file is different than the expected file"
}

run_suite "test tests"
