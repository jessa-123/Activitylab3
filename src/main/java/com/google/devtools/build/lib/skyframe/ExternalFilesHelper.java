// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.BundledFileSystem;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.TestType;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Common utilities for dealing with paths outside the package roots. */
public class ExternalFilesHelper {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final AtomicReference<PathPackageLocator> pkgLocator;
  private final ExternalFileAction externalFileAction;
  private final BlazeDirectories directories;
  private final int maxNumExternalFilesToLog;
  private final AtomicInteger numExternalFilesLogged = new AtomicInteger(0);
  private static final int MAX_EXTERNAL_FILES_TO_TRACK = 2500;

  // These variables are set to true from multiple threads, but only read in the main thread.
  // So volatility or an AtomicBoolean is not needed.
  private boolean anyOutputFilesSeen = false;
  private boolean tooManyNonOutputExternalFilesSeen = false;
  private boolean anyFilesInExternalReposSeen = false;

  // This is a set of external files that are not in external repositories.
  private Set<RootedPath> nonOutputExternalFilesSeen = Sets.newConcurrentHashSet();

  private ExternalFilesHelper(
      AtomicReference<PathPackageLocator> pkgLocator,
      ExternalFileAction externalFileAction,
      BlazeDirectories directories,
      int maxNumExternalFilesToLog) {
    this.pkgLocator = pkgLocator;
    this.externalFileAction = externalFileAction;
    this.directories = directories;
    this.maxNumExternalFilesToLog = maxNumExternalFilesToLog;
  }

  public static ExternalFilesHelper create(
      AtomicReference<PathPackageLocator> pkgLocator,
      ExternalFileAction externalFileAction,
      BlazeDirectories directories) {
    return TestType.isInTest()
        ? createForTesting(pkgLocator, externalFileAction, directories)
        : new ExternalFilesHelper(
            pkgLocator, externalFileAction, directories, /*maxNumExternalFilesToLog=*/ 100);
  }

  public static ExternalFilesHelper createForTesting(
      AtomicReference<PathPackageLocator> pkgLocator,
      ExternalFileAction externalFileAction,
      BlazeDirectories directories) {
    return new ExternalFilesHelper(
        pkgLocator,
        externalFileAction,
        directories,
        // These log lines are mostly spam during unit and integration tests.
        /*maxNumExternalFilesToLog=*/ 0);
  }


  /**
   * The action to take when an external path is encountered. See {@link FileType} for the
   * definition of "external".
   */
  public enum ExternalFileAction {
    /**
     * For paths of type {@link FileType#EXTERNAL_REPO}, introduce a Skyframe dependency on the
     * 'external' package.
     *
     * <p>This is the default for Bazel, since it's required for correctness of the external
     * repositories feature.
     */
    DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,

    /**
     * For paths of type {@link FileType#EXTERNAL} or {@link FileType#OUTPUT}, assume the path does
     * not exist and will never exist.
     */
    ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS,
  }

  /** Classification of a path encountered by Bazel. */
  public enum FileType {
    /**
     * A path to a file located inside of Bazel, e.g. the bundled builtins_bzl root (for {@code
     * --experimental_builtins_bzl_root=%bundled%}) that live in an InMemoryFileSystem. These files
     * must not change throughout the lifetime of the server.
     */
    BUNDLED,

    /** A path inside the package roots. */
    INTERNAL,

    /**
     * A non {@link #EXTERNAL_REPO} path outside the package roots about which we may make no other
     * assumptions.
     */
    EXTERNAL,

    /**
     * A path in Bazel's output tree that's a proper output of an action (*not* a source file in an
     * external repository). Such files are theoretically mutable, but certain Bazel flags may tell
     * Bazel to assume these paths are immutable.
     *
     * <p>Note that {@link ExternalFilesHelper#maybeHandleExternalFile} is only used for {@link
     * com.google.devtools.build.lib.actions.FileStateValue} and {@link DirectoryListingStateValue},
     * and also note that output files do not normally have corresponding {@link
     * com.google.devtools.build.lib.actions.FileValue} instances (and thus also {@link
     * com.google.devtools.build.lib.actions.FileStateValue} instances) in the Skyframe graph
     * ({@link ArtifactFunction} only uses {@link com.google.devtools.build.lib.actions.FileValue}s
     * for source files). But {@link com.google.devtools.build.lib.actions.FileStateValue}s for
     * output files can still make their way into the Skyframe graph if e.g. a source file is a
     * symlink to an output file.
     */
    // TODO(nharmata): Consider an alternative design where we have an OutputFileDiffAwareness. This
    // could work but would first require that we clean up all RootedPath usage.
    OUTPUT,

    /**
     * A path in the part of Bazel's output tree that contains (/ symlinks to) to external
     * repositories. Every such path under the external repository is generated by the execution of
     * the corresponding repository rule, so these paths should not be cached by Skyframe before the
     * RepositoryDirectoryValue is computed.
     */
    EXTERNAL_REPO,
  }

  /**
   * Thrown by {@link #maybeHandleExternalFile} when an applicable path is processed (see
   * {@link ExternalFileAction#ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS}.
   */
  static class NonexistentImmutableExternalFileException extends Exception {
  }

  static class ExternalFilesKnowledge {
    final boolean anyOutputFilesSeen;
    final Set<RootedPath> nonOutputExternalFilesSeen;
    final boolean anyFilesInExternalReposSeen;
    final boolean tooManyNonOutputExternalFilesSeen;

    private ExternalFilesKnowledge(
        boolean anyOutputFilesSeen,
        Set<RootedPath> nonOutputExternalFilesSeen,
        boolean anyFilesInExternalReposSeen,
        boolean tooManyNonOutputExternalFilesSeen) {
      this.anyOutputFilesSeen = anyOutputFilesSeen;
      this.nonOutputExternalFilesSeen = nonOutputExternalFilesSeen;
      this.anyFilesInExternalReposSeen = anyFilesInExternalReposSeen;
      this.tooManyNonOutputExternalFilesSeen = tooManyNonOutputExternalFilesSeen;
    }
  }

  @ThreadCompatible
  ExternalFilesKnowledge getExternalFilesKnowledge() {
    return new ExternalFilesKnowledge(
        anyOutputFilesSeen,
        nonOutputExternalFilesSeen,
        anyFilesInExternalReposSeen,
        tooManyNonOutputExternalFilesSeen);
  }

  @ThreadCompatible
  void setExternalFilesKnowledge(ExternalFilesKnowledge externalFilesKnowledge) {
    anyOutputFilesSeen = externalFilesKnowledge.anyOutputFilesSeen;
    nonOutputExternalFilesSeen = externalFilesKnowledge.nonOutputExternalFilesSeen;
    anyFilesInExternalReposSeen = externalFilesKnowledge.anyFilesInExternalReposSeen;
    tooManyNonOutputExternalFilesSeen = externalFilesKnowledge.tooManyNonOutputExternalFilesSeen;
  }

  ExternalFilesHelper cloneWithFreshExternalFilesKnowledge() {
    return new ExternalFilesHelper(
        pkgLocator, externalFileAction, directories, maxNumExternalFilesToLog);
  }

  public FileType getAndNoteFileType(RootedPath rootedPath) {
    return getFileTypeAndRepository(rootedPath).getFirst();
  }

  private Pair<FileType, RepositoryName> getFileTypeAndRepository(RootedPath rootedPath) {
    FileType fileType = detectFileType(rootedPath);
    if (FileType.EXTERNAL == fileType) {
      if (nonOutputExternalFilesSeen.size() >= MAX_EXTERNAL_FILES_TO_TRACK) {
        tooManyNonOutputExternalFilesSeen = true;
      } else {
        nonOutputExternalFilesSeen.add(rootedPath);
      }
    }
    if (FileType.EXTERNAL_REPO == fileType) {
      anyFilesInExternalReposSeen = true;
    }
    if (FileType.OUTPUT == fileType) {
      anyOutputFilesSeen = true;
    }
    return Pair.of(fileType, null);
  }

  /**
   * Returns the classification of a path, and updates this instance's state regarding what kinds of
   * paths have been seen.
   */
  private FileType detectFileType(RootedPath rootedPath) {
    if (rootedPath.getRoot().getFileSystem() instanceof BundledFileSystem) {
      return FileType.BUNDLED;
    }
    PathPackageLocator packageLocator = pkgLocator.get();
    if (packageLocator.getPathEntries().contains(rootedPath.getRoot())) {
      return FileType.INTERNAL;
    }
    // The outputBase may be null if we're not actually running a build.
    Path outputBase = packageLocator.getOutputBase();
    if (outputBase == null) {
      return FileType.EXTERNAL;
    }
    if (rootedPath.asPath().startsWith(outputBase)) {
      Path externalRepoDir = outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
      if (rootedPath.asPath().startsWith(externalRepoDir)) {
        return FileType.EXTERNAL_REPO;
      } else {
        return FileType.OUTPUT;
      }
    }
    return FileType.EXTERNAL;
  }

  /**
   * If this instance is configured with {@link
   * ExternalFileAction#DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS} and {@code rootedPath} isn't
   * under a package root then this adds a dependency on the //external package. If the action is
   * {@link ExternalFileAction#ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS}, it will throw
   * a {@link NonexistentImmutableExternalFileException} instead.
   */
  @ThreadSafe
  FileType maybeHandleExternalFile(RootedPath rootedPath, SkyFunction.Environment env)
      throws NonexistentImmutableExternalFileException, IOException, InterruptedException {
    Pair<FileType, RepositoryName> pair = getFileTypeAndRepository(rootedPath);

    FileType fileType = Preconditions.checkNotNull(pair.getFirst());
    switch (fileType) {
      case BUNDLED:
      case INTERNAL:
        break;
      case EXTERNAL:
        if (numExternalFilesLogged.incrementAndGet() < maxNumExternalFilesToLog) {
          logger.atInfo().log("Encountered an external path %s", rootedPath);
        }
        // fall through
      case OUTPUT:
        if (externalFileAction
            == ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS) {
          throw new NonexistentImmutableExternalFileException();
        }
        break;
      case EXTERNAL_REPO:
        Preconditions.checkState(
            externalFileAction == ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            externalFileAction);
        RepositoryFunction.addExternalFilesDependencies(rootedPath, directories, env);
        break;
    }
    return fileType;
  }
}
