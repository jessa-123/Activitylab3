// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineLimits;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Utility methods that are the canonical way for actions to support path mapping (see {@link
 * PathMapper}).
 */
public final class PathMappers {
  // TODO: Remove actions from this list by adding ExecutionRequirements.SUPPORTS_PATH_MAPPING to
  //  their execution info instead.
  private static final ImmutableSet<String> SUPPORTED_MNEMONICS =
      ImmutableSet.of(
          "AndroidLint",
          "CompileAndroidResources",
          "DeJetify",
          "DejetifySrcs",
          "Desugar",
          "DexBuilder",
          "Jetify",
          "JetifySrcs",
          "LinkAndroidResources",
          "MergeAndroidAssets",
          "MergeManifests",
          "ParseAndroidResources",
          "StarlarkAARGenerator",
          "StarlarkMergeCompiledAndroidResources",
          "StarlarkRClassGenerator",
          "Mock action");

  /**
   * Actions that support path mapping should call this method when creating their {@link Spawn}.
   *
   * <p>The returned {@link PathMapper} has to be passed to {@link
   * com.google.devtools.build.lib.actions.CommandLine#arguments(ArtifactExpander, PathMapper)},
   * {@link com.google.devtools.build.lib.actions.CommandLines#expand(ArtifactExpander,
   * PathFragment, PathMapper, CommandLineLimits)} )} or any other variants of these functions. The
   * same instance should also be passed to the {@link Spawn} constructor so that the executor can
   * obtain it via {@link Spawn#getPathMapper()}.
   *
   * <p>Note: This method flattens nested sets and should thus not be called from methods that are
   * executed in the analysis phase.
   *
   * <p>Actions calling this method should also call {@link
   * PathMapper#addToFingerprint(Fingerprint)} from their {@link Action#getKey(ActionKeyContext,
   * ArtifactExpander)} to ensure correct incremental builds.
   *
   * @param action the {@link AbstractAction} for which a {@link Spawn} is to be created
   * @param outputPathsMode the value of {@link CoreOptions#outputPathsMode}
   * @return a {@link PathMapper} that maps paths of the action's inputs and outputs. May be {@link
   *     PathMapper#NOOP} if path mapping is not applicable to the action.
   */
  public static PathMapper create(AbstractAction action, OutputPathsMode outputPathsMode) {
    return createInternal(action, outputPathsMode, /* forFingerprint= */ false);
  }

  /**
   * Actions that support path mapping should call this method from {@link
   * Action#getKey(ActionKeyContext, ArtifactExpander)}.
   *
   * <p>Compared to {@link #create(AbstractAction, OutputPathsMode)}, this method does not flatten
   * and iterate over the set of inputs and thus avoids memory and CPU regressions.
   *
   * @param action the {@link AbstractAction} for which a {@link Spawn} is to be created
   * @param outputPathsMode the value of {@link CoreOptions#outputPathsMode}
   */
  public static PathMapper createForFingerprint(
      AbstractAction action, OutputPathsMode outputPathsMode) {
    return createInternal(action, outputPathsMode, /* forFingerprint= */ true);
  }

  private static PathMapper createInternal(
      AbstractAction action, OutputPathsMode outputPathsMode, boolean forFingerprint) {
    if (getEffectiveOutputPathsMode(
            outputPathsMode, action.getMnemonic(), action.getExecutionInfo())
        != OutputPathsMode.STRIP) {
      return PathMapper.NOOP;
    }
    return StrippingPathMapper.tryCreate(action, forFingerprint).orElse(PathMapper.NOOP);
  }

  /**
   * Helper method to simplify calling {@link #create} for actions that store the configuration
   * directly.
   *
   * @param configuration the configuration
   * @return the value of
   */
  public static OutputPathsMode getOutputPathsMode(
      @Nullable BuildConfigurationValue configuration) {
    if (configuration == null) {
      return OutputPathsMode.OFF;
    }
    return configuration.getOptions().get(CoreOptions.class).outputPathsMode;
  }

  private static OutputPathsMode getEffectiveOutputPathsMode(
      OutputPathsMode outputPathsMode, String mnemonic, Map<String, String> executionInfo) {
    if (outputPathsMode == OutputPathsMode.STRIP
        && (SUPPORTED_MNEMONICS.contains(mnemonic)
            || executionInfo.containsKey(ExecutionRequirements.SUPPORTS_PATH_MAPPING))) {
      return OutputPathsMode.STRIP;
    }
    return OutputPathsMode.OFF;
  }

  private PathMappers() {}
}
