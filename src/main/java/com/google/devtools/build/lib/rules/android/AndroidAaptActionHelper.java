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
package com.google.devtools.build.lib.rules.android;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.rules.android.ResourceContainer.ResourceType;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Helper class to generate Android aapt actions.
 */
public final class AndroidAaptActionHelper {
  private final RuleContext ruleContext;
  private final Artifact manifest;
  private final Collection<Artifact> inputs = new LinkedHashSet<>();
  private final Iterable<ResourceContainer> resourceContainers;

  /**
   * Constructs an instance of AndroidAaptActionHelper.
   *
   * @param ruleContext RuleContext for which the aapt actions
   *        will be generated.
   * @param manifest Artifact representing the AndroidManifest.xml that will be
   *        used to package resources.
   * @param resourceContainers The transitive closure of the ResourceContainers.
   */
  public AndroidAaptActionHelper(RuleContext ruleContext, Artifact manifest,
      Iterable<ResourceContainer> resourceContainers) {
    this.ruleContext = ruleContext;
    this.manifest = manifest;
    this.resourceContainers = resourceContainers;
  }

  /**
   * Returns the artifacts needed as inputs to process the resources/assets.
   */
  private Iterable<Artifact> getInputs() {
    if (inputs.isEmpty()) {
      inputs.add(AndroidSdkProvider.fromRuleContext(ruleContext).getAndroidJar());
      inputs.add(manifest);
      Iterables.addAll(
          inputs,
          Iterables.concat(
              Iterables.transform(resourceContainers, ResourceContainer::getArtifacts)));
    }
    return inputs;
  }

  /**
   * Creates an Action that will invoke aapt to generate symbols java sources from the
   * resources and pack them into a srcjar.
   * @param javaSourcesJar Artifact to be generated by executing the action
   *        created by this method.
   * @param rTxt R.txt artifact to be generated by the aapt invocation.
   * @param javaPackage The package for which resources will be generated
   * @param inlineConstants whether or not constants in Java generated sources
   *        should be inlined by the compiler.
   */
  public void createGenerateResourceSymbolsAction(Artifact javaSourcesJar,
      Artifact rTxt, String javaPackage, boolean inlineConstants) {
    // java path from the provided package for the resources
    PathFragment javaPath = PathFragment.create(javaPackage.replace('.', '/'));

    PathFragment javaResourcesRoot = javaSourcesJar.getRoot().getExecPath().getRelative(
        ruleContext.getUniqueDirectory("_java_resources"));

    String javaResources = javaResourcesRoot.getRelative(javaPath).getPathString();

    List<String> args = new ArrayList<>();
    args.add(javaSourcesJar.getExecPathString());
    args.add(javaResourcesRoot.getPathString());
    args.add(javaResources);

    args.addAll(createAaptCommand("javasrcs", javaSourcesJar, rTxt, inlineConstants,
        "-J", javaResources, "--custom-package", javaPackage, "--rename-manifest-package",
        javaPackage));
    final Builder builder =
        new SpawnAction.Builder()
            .addInputs(getInputs())
            .addTool(AndroidSdkProvider.fromRuleContext(ruleContext).getAapt())
            .setExecutable(
                ruleContext.getExecutablePrerequisite("$android_aapt_java_generator", Mode.HOST))
            .addOutput(javaSourcesJar)
            .setCommandLine(CommandLine.of(args))
            .useParameterFile(ParameterFileType.UNQUOTED)
            .setProgressMessage("Generating Java resources")
            .setMnemonic("AaptJavaGenerator");
    if (rTxt != null) {
      builder.addOutput(rTxt);
    }
    ruleContext.registerAction(builder.build(ruleContext));
  }

  /**
   * Creates an Action that will invoke aapt to package the android resources
   * into an apk file.
   * @param apk Packed resources artifact to be generated by the aapt invocation.
   */
  public void createGenerateApkAction(Artifact apk, String renameManifestPackage,
      List<String> aaptOpts, List<String> densities) {
    List<String> args;

    if (renameManifestPackage == null) {
      args = createAaptCommand("apk", apk, null, true, "-F", apk.getExecPathString());
    } else {
      args = createAaptCommand("apk", apk, null, true, "-F",
          apk.getExecPathString(), "--rename-manifest-package", renameManifestPackage);
    }

    if (!densities.isEmpty()) {
      args.add(0, "start_densities");
      args.add(1, "end_densities");
      args.addAll(1, densities);
    }

    args.addAll(aaptOpts);

    ruleContext.registerAction(
        new SpawnAction.Builder()
            .addInputs(getInputs())
            .addTool(AndroidSdkProvider.fromRuleContext(ruleContext).getAapt())
            .addOutput(apk)
            .setExecutable(
                ruleContext.getExecutablePrerequisite("$android_aapt_apk_generator", Mode.HOST))
            .setCommandLine(CommandLine.of(args))
            .useParameterFile(ParameterFileType.UNQUOTED)
            .setProgressMessage("Generating apk resources")
            .setMnemonic("AaptResourceApk")
            .build(ruleContext));
  }

  private List<String> createAaptCommand(String actionKind, Artifact output,
      Artifact rTxtOutput, boolean inlineConstants, String... outputArgs) {
    return createAaptCommand(
        actionKind, output, rTxtOutput, inlineConstants, Arrays.asList(outputArgs));
  }

  private List<String> createAaptCommand(String actionKind, Artifact output,
      Artifact rTxtOutput, boolean inlineConstants, Collection<String> outputArgs) {
    List<String> args = new ArrayList<>();
    args.addAll(getArgs(output, actionKind, ResourceType.RESOURCES));
    args.addAll(getArgs(output, actionKind, ResourceType.ASSETS));
    args.add(
        AndroidSdkProvider.fromRuleContext(ruleContext).getAapt().getExecutable().getExecPathString());
    args.add("package");
    args.addAll(outputArgs);
    // Allow overlay in case the same resource appears in more than one target,
    // giving precedence to the order in which they are found. This is needed
    // in order to support android library projects.
    args.add("--auto-add-overlay");
    if (rTxtOutput != null) {
      args.add("--output-text-symbols");
      args.add(rTxtOutput.getExecPath().getParentDirectory().getPathString());
    }
    if (!inlineConstants) {
      args.add("--non-constant-id");
    }
    if (ruleContext.getConfiguration().getCompilationMode() != CompilationMode.OPT) {
      args.add("--debug-mode");
    }
    args.add("-I");
    args.add(AndroidSdkProvider.fromRuleContext(ruleContext).getAndroidJar().getExecPathString());
    args.add("-M");
    args.add(manifest.getExecPathString());
    args.addAll(getResourcesDirArg(output, actionKind, "-S", ResourceType.RESOURCES));
    args.addAll(getResourcesDirArg(output, actionKind, "-A", ResourceType.ASSETS));
    return args;
  }

  @VisibleForTesting
  public List<String> getArgs(Artifact output, String actionKind, ResourceType resourceType) {
    PathFragment outputPath = outputPath(output, actionKind, resourceType);
    List<String> args = new ArrayList<>();
    args.add("start_" + resourceType.getAttribute());
    args.add(outputPath.getPathString());
    // First make sure path elements are unique
    Collection<String> paths = new LinkedHashSet<>();
    for (ResourceContainer container : resourceContainers) {
      for (Artifact artifact : container.getArtifacts(resourceType)) {
        paths.add(artifact.getExecPathString());
      }
    }
    // Than populate the command line
    for (String path : paths) {
      args.add(path);
      args.add(path);
    }
    args.add("end_" + resourceType.getAttribute());

    // if there is at least one artifact
    if (args.size() > 3) {
      return ImmutableList.copyOf(args);
    } else {
      return ImmutableList.of();
    }
  }

  /**
   * Returns optional part of the <code>aapt</code> command line:
   * optionName output_path.
   */
  @VisibleForTesting
  public List<String> getResourcesDirArg(Artifact output, String actionKind, String resourceArg,
      ResourceType resourceType) {
    PathFragment outputPath = outputPath(output, actionKind, resourceType);
    List<String> dirArgs = new ArrayList<>();
    Collection<String> paths = new LinkedHashSet<>();
    // First make sure roots are unique
    for (ResourceContainer container : resourceContainers) {
      for (PathFragment root : container.getRoots(resourceType)) {
        paths.add(outputPath.getRelative(root).getPathString());
      }
    }
    // Than populate the command line
    for (String path : paths) {
      dirArgs.add(resourceArg);
      dirArgs.add(path);
    }

    return ImmutableList.copyOf(dirArgs);
  }

  /**
   * Returns a resourceType specific unique output location for the given action kind.
   */
  private PathFragment outputPath(Artifact output, String actionKind, ResourceType resourceType) {
    return output.getRoot().getExecPath().getRelative(ruleContext.getUniqueDirectory(
        "_" + resourceType.getAttribute() + "_" + actionKind));
  }

  public void createGenerateProguardAction(
      Artifact outputSpec, @Nullable Artifact outputMainDexSpec) {
    ImmutableList.Builder<Artifact> outputs = ImmutableList.builder();
    ImmutableList.Builder<String> aaptArgs = ImmutableList.builder();

    outputs.add(outputSpec);
    aaptArgs.add("-G").add(outputSpec.getExecPathString());

    if (outputMainDexSpec != null) {
      aaptArgs.add("-D").add(outputMainDexSpec.getExecPathString());
      outputs.add(outputMainDexSpec);
    }

    List<String> aaptCommand =
        createAaptCommand("proguard", outputSpec, null, true, aaptArgs.build());
    ruleContext.registerAction(
        new SpawnAction.Builder()
            .addInputs(getInputs())
            .addTool(AndroidSdkProvider.fromRuleContext(ruleContext).getAapt())
            .addOutputs(outputs.build())
            .setExecutable(
                ruleContext.getExecutablePrerequisite("$android_aapt_apk_generator", Mode.HOST))
            .setCommandLine(CommandLine.of(aaptCommand))
            .useParameterFile(ParameterFileType.UNQUOTED)
            .setProgressMessage("Generating Proguard configuration for resources")
            .setMnemonic("AaptProguardConfiguration")
            .build(ruleContext));
  }
}
