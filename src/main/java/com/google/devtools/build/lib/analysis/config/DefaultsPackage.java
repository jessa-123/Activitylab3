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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A helper class to compute and inject a defaults package into the package cache.
 *
 * <p>The <code>//tools/defaults</code> package provides a mechanism let tool locations be specified
 * over the commandline, without requiring any special support in the rule code. As such, it can be
 * used in genrule <code>$(location)</code> substitutions.
 *
 * <p>It works as follows:
 *
 * <ul>
 *   <li>SomeLanguage.createCompileAction will refer to a host-configured target for the compiler by
 *       looking for <code>env.getHostPrerequisiteArtifact("$somelanguage_compiler")</code>.
 *   <li>the attribute <code>$somelanguage_compiler</code> is defined in the {@link RuleDefinition}
 *       subclass for that language.
 *   <li>if the attribute cannot be set on the command-line, its value may be a normal label.
 *   <li>if the attribute can be set on the command-line, its value will be <code>
 *       //tools/defaults:somelanguage_compiler</code>.
 *   <li>in the latter case, the {@link BuildConfiguration.Fragment} subclass will define the option
 *       (with an existing target, eg. <code>//third_party/somelanguage:compiler</code>), and return
 *       the name in its implementation of {@link FragmentOptions#getDefaultsLabels}.
 *   <li>On startup, the rule is wired up with <code>//tools/defaults:somelanguage_compiler</code>.
 *   <li>On starting a build, the <code>//tools/defaults</code> package is synthesized, using the
 *       values as specified on the command-line. The contents of <code>tools/defaults/BUILD</code>
 *       is ignored.
 *   <li>Hence, changes in the command line values for tools are now handled exactly as if they were
 *       changes in a BUILD file.
 *   <li>The file <code>tools/defaults/BUILD</code> must exist, so we create a package in that
 *       location.
 *   <li>The code in {@link DefaultsPackage} can dump the synthesized package as a BUILD file, so
 *       external tooling does not need to understand the intricacies of handling command-line
 *       options.
 * </ul>
 *
 * <p>For built-in rules (as opposed to genrules), late-bound labels provide an alternative method
 * of depending on command-line values. These work by declaring attribute default values to be
 * {@link LateBoundDefault} instances, whose <code>resolve(Rule rule, AttributeMap attributes,
 * FragmentT configuration)</code> method will have access to a {@link BuildConfiguration.Fragment},
 * which in turn may depend on command line flag values.
 */
public final class DefaultsPackage {

  // The template contents are broken into lines such that the resulting file has no more than 80
  // characters per line.
  private static final String HEADER = ""
      + "# DO NOT EDIT THIS FILE!\n"
      + "#\n"
      + "# Bazel does not read this file. Instead, it internally replaces the targets in\n"
      + "# this package with the correct packages as given on the command line.\n"
      + "#\n"
      + "# If these options are not given on the command line, Bazel will use the exact\n"
      + "# same targets as given here."
      + "\n"
      + "package(default_visibility = ['//visibility:public'])\n";

  /**
   * The map from entries to their values.
   */
  private ImmutableMap<String, ImmutableSet<Label>> values;
  private ImmutableList<String> rules;

  private DefaultsPackage(BuildOptions buildOptions) {
    values = buildOptions.getDefaultsLabels();
    rules = buildOptions.getDefaultsRules();
  }

  private String labelsToString(Set<Label> labels) {
    StringBuilder result = new StringBuilder();
    for (Label label : labels) {
      if (result.length() != 0) {
        result.append(", ");
      }
      result.append("'").append(label).append("'");
    }
    return result.toString();
  }

  /**
   * Returns a string of the defaults package with the given settings.
   */
  private String getContent() {
    Preconditions.checkState(!values.isEmpty());
    StringBuilder result = new StringBuilder(HEADER);
    for (Map.Entry<String, ImmutableSet<Label>> entry : values.entrySet()) {
      result
          .append("filegroup(name = '")
          .append(entry.getKey().toLowerCase(Locale.US)).append("',\n")
          .append("          srcs = [")
          .append(labelsToString(entry.getValue())).append("])\n");
    }

    for (String rule : rules) {
      result.append(rule).append("\n");
    }

    return result.toString();
  }

  /**
   * Returns the defaults package for the default settings.
   */
  public static String getDefaultsPackageContent(
      Iterable<Class<? extends FragmentOptions>> options, InvocationPolicy invocationPolicy) {
    return getDefaultsPackageContent(BuildOptions.createDefaults(options, invocationPolicy));
  }

  /**
   * Returns the defaults package for the given options.
   */
  public static String getDefaultsPackageContent(BuildOptions buildOptions) {
    return new DefaultsPackage(buildOptions).getContent();
  }

  public static void parseAndAdd(Set<Label> labels, String optionalLabel) {
    if (optionalLabel != null) {
      Label label = parseOptionalLabel(optionalLabel);
      if (label != null) {
        labels.add(label);
      }
    }
  }

  public static Label parseOptionalLabel(String value) {
    try {
      return Label.parseAbsolute(value);
    } catch (LabelSyntaxException e) {
      // We ignore this exception here - it will cause an error message at a later time.
      return null;
    }
  }
}
