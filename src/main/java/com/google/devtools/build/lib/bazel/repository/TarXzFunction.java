// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository;

import com.google.devtools.build.lib.bazel.repository.DecompressorValue.Decompressor;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import org.tukaani.xz.XZInputStream;

/**
 * Creates a repository by unarchiving a .tar.xz file.
 */
class TarXzFunction extends CompressedTarFunction {
  public static final Decompressor INSTANCE = new TarXzFunction();
  private static final int BUFFER_SIZE = 32 * 1024;

  private TarXzFunction() {
  }

  @Override
  protected InputStream getDecompressorStream(DecompressorDescriptor descriptor)
      throws IOException {
    return new XZInputStream(
        new BufferedInputStream(
            Files.newInputStream(descriptor.archivePath().getPathFile().toPath()), BUFFER_SIZE));
  }
}
