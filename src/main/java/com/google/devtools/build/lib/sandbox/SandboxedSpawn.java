// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import com.google.devtools.build.lib.util.DescribableExecutionUnit;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A description of a subprocess, as well as the necessary file system / sandbox setup.
 *
 * <p>Instances are responsible for making a list of input files available inside the sandbox root,
 * so that a process running inside the directory can access the files. It also handles moving the
 * output files generated by the process out of the directory into a destination directory.
 */
interface SandboxedSpawn extends DescribableExecutionUnit {
  /** The path in which to execute the subprocess. */
  Path getSandboxExecRoot();

  /** Returns {@code true}, if the runner should use the Subprocess timeout feature. */
  default boolean useSubprocessTimeout() {
    return false;
  }

  /** Returns the path that sandbox debug output is written to, if any. */
  @Nullable
  Path getSandboxDebugPath();

  /** Returns the path where statistics about subprocess execution are written, if any. */
  @Nullable
  Path getStatisticsPath();

  /**
   * Creates the sandboxed execution root, making all {@code inputs} available for reading, making
   * sure that the parent directories of all {@code outputs} and that all {@code writableDirs} exist
   * and can be written into.
   *
   * @throws IOException
   */
  void createFileSystem() throws IOException, InterruptedException;

  /**
   * Moves all {@code outputs} to {@code execRoot} while keeping the directory structure.
   *
   * @throws IOException
   */
  void copyOutputs(Path execRoot) throws IOException;

  /** Deletes the sandbox directory. */
  void delete();

  /**
   * Returns user-facing instructions for starting an interactive sandboxed environment identical to
   * the one in which this spawn is executed.
   */
  default Optional<String> getInteractiveDebugInstructions() {
    return Optional.empty();
  }
}
