// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates an execRoot for a Spawn by copying input files.
 */
public class WindowsSandboxedSpawn extends AbstractContainerizingSandboxedSpawn {

  public WindowsSandboxedSpawn(
      Path sandboxPath,
      Path sandboxExecRoot,
      List<String> arguments,
      Map<String, String> environment,
      Map<PathFragment, Path> inputs,
      SandboxOutputs outputs,
      Set<Path> writableDirs,
      TreeDeleter treeDeleter) {
    super(
        sandboxPath,
        sandboxExecRoot,
        arguments,
        environment,
        inputs,
        outputs,
        writableDirs,
        treeDeleter);
  }

  @Override
  protected void copyFile(Path source, Path target) throws IOException {
    FileStatus stat = source.stat(Symlinks.NOFOLLOW);
    if (stat.isSymbolicLink() || stat.isFile()) {
      FileSystemUtils.copyFile(source, target);
    } else if (stat.isDirectory()) {
      target.createDirectory();
      FileSystemUtils.copyTreesBelow(source, target, Symlinks.NOFOLLOW);
    }
  }
}
