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

package com.google.devtools.build.lib.skylarkbuildapi.python;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.skylarkbuildapi.ProviderApi;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkConstructor;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import javax.annotation.Nullable;

/** Provider instance for {@code py_runtime}. */
@SkylarkModule(
    name = "PyRuntimeInfo",
    doc =
        "Contains information about a Python runtime, as returned by the <code>py_runtime</code>"
            + "rule."
            + ""
            + "<p>A Python runtime describes either a <em>platform runtime</em> or an <em>in-build "
            + "runtime</em>. A platform runtime accesses a system-installed interpreter at a known "
            + "path, whereas an in-build runtime points to a <code>File</code> that acts as the "
            + "interpreter. In both cases, an \"interpreter\" is really any executable binary or "
            + "wrapper script that is capable of running a Python script passed on the command "
            + "line, following the same conventions as the standard CPython interpreter.",
    category = SkylarkModuleCategory.PROVIDER)
public interface PyRuntimeInfoApi<FileT extends FileApi> extends SkylarkValue {

  @SkylarkCallable(
      name = "interpreter_path",
      structField = true,
      allowReturnNones = true,
      doc =
          "If this is a platform runtime, this field is the absolute filesystem path to the "
              + "interpreter on the target platform. Otherwise, this is <code>None</code>.")
  @Nullable
  String getInterpreterPathString();

  @SkylarkCallable(
      name = "interpreter",
      structField = true,
      allowReturnNones = true,
      doc =
          "If this is an in-build runtime, this field is a <code>File</code> representing the "
              + "interpreter. Otherwise, this is <code>None</code>. Note that an in-build runtime "
              + "can use either a prebuilt, checked-in interpreter or an interpreter built from "
              + "source.")
  @Nullable
  FileT getInterpreter();

  @SkylarkCallable(
      name = "files",
      structField = true,
      allowReturnNones = true,
      doc =
          "If this is an in-build runtime, this field is a <code>depset</code> of <code>File"
              + "</code>s that need to be added to the runfiles of an executable target that uses "
              + "this runtime (in particular, files needed by <code>interpreter</code>). The value "
              + "of <code>interpreter</code> need not be included in this "
              + "field. If this is a platform runtime then this field is <code>None</code>.")
  @Nullable
  SkylarkNestedSet getFilesForStarlark();

  /** Provider type for {@link PyRuntimeInfoApi} objects. */
  @SkylarkModule(name = "Provider", documented = false, doc = "")
  interface PyRuntimeInfoProviderApi extends ProviderApi {

    @SkylarkCallable(
        name = "PyRuntimeInfo",
        doc = "The <code>PyRuntimeInfo</code> constructor.",
        parameters = {
          @Param(
              name = "interpreter_path",
              type = String.class,
              noneable = true,
              positional = false,
              named = true,
              defaultValue = "None",
              doc =
                  "The value for the new object's <code>interpreter_path</code> field. Do not give "
                      + "a value for this argument if you pass in <code>interpreter</code>."),
          @Param(
              name = "interpreter",
              type = FileApi.class,
              noneable = true,
              positional = false,
              named = true,
              defaultValue = "None",
              doc =
                  "The value for the new object's <code>interpreter</code> field. Do not give "
                      + "a value for this argument if you pass in <code>interpreter_path</code>."),
          @Param(
              name = "files",
              type = SkylarkNestedSet.class,
              generic1 = FileApi.class,
              noneable = true,
              positional = false,
              named = true,
              defaultValue = "None",
              doc =
                  "The value for the new object's <code>files</code> field. Do not give a value "
                      + "for this argument if you pass in <code>interpreter_path</code>. If "
                      + "<code>interpreter</code> is given and this argument is <code>None</code>, "
                      + "<code>files</code> becomes an empty <code>depset</code> instead."),
        },
        selfCall = true,
        useLocation = true)
    @SkylarkConstructor(objectType = PyRuntimeInfoApi.class, receiverNameForDoc = "PyRuntimeInfo")
    PyRuntimeInfoApi<?> constructor(
        Object interpreterPathUncast, Object interpreterUncast, Object filesUncast, Location loc)
        throws EvalException;
  }
}
