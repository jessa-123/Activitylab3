// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.blackbox.framework;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Abstract class for setting up the blackbox test environment, returns {@link BlackBoxTestContext}
 * for the tests to access the environment and run Bazel/Blaze commands. This is the single entry
 * point in the blackbox tests framework for working with test environment.
 *
 * <p>The single instance of this class can be used for several tests. Each test should call {@link
 * #prepareEnvironment(String, ImmutableList)} to prepare environment and obtain the test context.
 *
 * <p>See {@link com.google.devtools.build.lib.blackbox.junit.AbstractBlackBoxTest}
 */
public abstract class BlackBoxTestEnvironment {
  /**
   * Executor service for reading stdout and stderr streams of the process. Has exactly two threads
   * since there are two streams.
   */
  @Nullable
  private ExecutorService executorService =
      MoreExecutors.getExitingExecutorService(
          (ThreadPoolExecutor) Executors.newFixedThreadPool(2),
          1, TimeUnit.SECONDS);

  protected abstract BlackBoxTestContext prepareEnvironment(
      String testName, ImmutableList<ToolsSetup> tools, ExecutorService executorService)
      throws Exception;

  /**
   * Prepares test environment and returns test context instance to be used by tests to access the
   * environment and invoke Bazel/Blaze commands.
   *
   * @param testName name of the current test, used to name the test working directory
   * @param tools the list of all tools setup classes {@link ToolsSetup} that should be called for
   *     the test
   * @return {@link BlackBoxTestContext} test context
   * @throws Exception if tools setup fails
   */
  public BlackBoxTestContext prepareEnvironment(String testName, ImmutableList<ToolsSetup> tools)
      throws Exception {
    Preconditions.checkNotNull(executorService);
    return prepareEnvironment(testName, tools, executorService);
  }

  /**
   * This method must be called when the test group execution is finished, for example, from
   * &#64;AfterClass method.
   */
  public final void dispose() {
    Preconditions.checkNotNull(executorService);
    MoreExecutors.shutdownAndAwaitTermination(executorService, 1, TimeUnit.SECONDS);
    executorService = null;
  }

  public static String getWorkspaceWithDefaultRepos() {
    return Joiner.on("\n")
        .join(
            "load('@bazel_tools//tools/build_defs/repo:http.bzl', 'http_archive')",
            "http_archive(",
            "    name = 'rules_cc',",
            "    sha256 = '36fa66d4d49debd71d05fba55c1353b522e8caef4a20f8080a3d17cdda001d89',",
            "    strip_prefix = 'rules_cc-0d5f3f2768c6ca2faca0079a997a97ce22997a0c',",
            "    urls = [",
            "        'https://mirror.bazel.build/github.com/bazelbuild/rules_cc/archive/"
                + "0d5f3f2768c6ca2faca0079a997a97ce22997a0c.zip',",
            "        'https://github.com/bazelbuild/rules_cc/archive/"
                + "0d5f3f2768c6ca2faca0079a997a97ce22997a0c.zip',",
            "    ],",
            ")",
            "http_archive(",
            "   name = \"rules_pkg\",",
            "   sha256 = \"5bdc04987af79bd27bc5b00fe30f59a858f77ffa0bd2d8143d5b31ad8b1bd71c\",",
            "   urls = [",
            "       \"https://mirror.bazel.build/github.com/bazelbuild/rules_pkg/rules_pkg-0.2.0.tar.gz\",",
            "       \"https://github.com/bazelbuild/rules_pkg/releases/download/0.2.0/rules_pkg-0.2.0.tar.gz\",",
            "   ],",
            ")",
            "load(\"@rules_pkg//:deps.bzl\", \"rules_pkg_dependencies\")",
            "rules_pkg_dependencies()"
            );
  }
}
