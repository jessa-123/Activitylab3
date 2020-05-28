// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skylarkbuildapi.proto;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skylarkbuildapi.ProtoInfoApi.ProtoInfoProviderApi;
import com.google.devtools.build.lib.skylarkbuildapi.StarlarkAspectApi;
import com.google.devtools.build.lib.skylarkbuildapi.core.Bootstrap;
import com.google.devtools.build.lib.skylarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.syntax.FlagGuardedValue;
import com.google.devtools.build.lib.syntax.StarlarkSemantics.FlagIdentifier;

/** A {@link Bootstrap} for Starlark objects related to protocol buffers. */
public class ProtoBootstrap implements Bootstrap {

  /** The name of the proto info provider in Starlark. */
  public static final String PROTO_INFO_STARLARK_NAME = "ProtoInfo";

  /** The name of the proto namespace in Starlark. */
  public static final String PROTO_COMMON_NAME = "proto_common";

  private final ProtoInfoProviderApi protoInfoApiProvider;
  private final Object protoCommon;
  private final StarlarkAspectApi protoRegistryAspect;
  private final ProviderApi protoRegistryProvider;

  public ProtoBootstrap(
      ProtoInfoProviderApi protoInfoApiProvider,
      Object protoCommon,
      StarlarkAspectApi protoRegistryAspect,
      ProviderApi protoRegistryProvider) {
    this.protoInfoApiProvider = protoInfoApiProvider;
    this.protoCommon = protoCommon;
    this.protoRegistryAspect = protoRegistryAspect;
    this.protoRegistryProvider = protoRegistryProvider;
  }

  @Override
  public void addBindingsToBuilder(ImmutableMap.Builder<String, Object> builder) {
    builder.put(PROTO_INFO_STARLARK_NAME, protoInfoApiProvider);
    builder.put(PROTO_COMMON_NAME, protoCommon);
    builder.put(
        "ProtoRegistryAspect",
        FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(
            FlagIdentifier.EXPERIMENTAL_GOOGLE_LEGACY_API, protoRegistryAspect));
    builder.put(
        "ProtoRegistryProvider",
        FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(
            FlagIdentifier.EXPERIMENTAL_GOOGLE_LEGACY_API, protoRegistryProvider));
  }
}
