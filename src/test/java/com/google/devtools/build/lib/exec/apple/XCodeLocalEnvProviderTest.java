// Copyright 2017 The Bazel Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec.apple;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import java.io.IOException;
import org.junit.Test;

/**
 * Tests for {@link XCodeLocalEnvProvider}.
 */
public class XCodeLocalEnvProviderTest {
  private final FileSystem fs = new JavaIoFileSystem();

  @Test
  public void testIOSEnvironmentOnNonDarwin() throws Exception {
    if (OS.getCurrent() == OS.DARWIN) {
      return;
    }
    try {
      new XCodeLocalEnvProvider().rewriteLocalEnv(
          ImmutableMap.<String, String>of(
              AppleConfiguration.APPLE_SDK_VERSION_ENV_NAME, "8.4",
              AppleConfiguration.APPLE_SDK_PLATFORM_ENV_NAME, "iPhoneSimulator"),
          fs.getPath("/tmp"),
          "bazel");
      fail("action should fail due to being unable to resolve SDKROOT");
    } catch (IOException e) {
      assertThat(e)
          .hasMessageThat()
          .contains("Cannot locate iOS SDK on non-darwin operating system");
    }
  }
}
