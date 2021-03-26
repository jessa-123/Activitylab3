# pylint: disable=g-bad-file-header
# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Configuring the C++ toolchain on Unix platforms."""

load(
    "@bazel_tools//tools/cpp:lib_cc_configure.bzl",
    "auto_configure_fail",
    "auto_configure_warning",
    "auto_configure_warning_maybe",
    "escape_string",
    "execute",
    "get_env_var",
    "get_starlark_list",
    "resolve_labels",
    "split_escaped",
    "which",
    "write_builtin_include_directory_paths",
)

def _uniq(iterable):
    """Remove duplicates from a list."""

    unique_elements = {element: None for element in iterable}
    return unique_elements.keys()

def _generate_system_module_map(repository_ctx, dirs, script_path):
    return execute(repository_ctx, [script_path] + dirs)

def _prepare_include_path(repo_ctx, path):
    """Resolve include path before outputting it into the crosstool.

    Args:
      repo_ctx: repository_ctx object.
      path: an include path to be resolved.

    Returns:
      Resolved include path. Resulting path is absolute if it is outside the
      repository and relative otherwise.
    """

    repo_root = str(repo_ctx.path("."))

    # We're on UNIX, so the path delimiter is '/'.
    repo_root += "/"
    path = str(repo_ctx.path(path))
    if path.startswith(repo_root):
        return path[len(repo_root):]
    return path

def _get_value(it):
    """Convert `it` in serialized protobuf format."""
    if type(it) == "int":
        return str(it)
    elif type(it) == "bool":
        return "true" if it else "false"
    else:
        return "\"%s\"" % it

def _find_tool(repository_ctx, tool, overriden_tools):
    """Find a tool for repository, taking overriden tools into account."""
    if tool in overriden_tools:
        return overriden_tools[tool]
    return which(repository_ctx, tool, "/usr/bin/" + tool)

def _get_tool_paths(repository_ctx, overriden_tools):
    """Compute the %-escaped path to the various tools"""
    return dict({
        k: escape_string(_find_tool(repository_ctx, k, overriden_tools))
        for k in [
            "ar",
            "ld",
            "llvm-cov",
            "cpp",
            "gcc",
            "dwp",
            "gcov",
            "nm",
            "objcopy",
            "objdump",
            "strip",
        ]
    }.items())

def _escaped_cplus_include_paths(repository_ctx):
    """Use ${CPLUS_INCLUDE_PATH} to compute the %-escaped list of flags for cxxflag."""
    if "CPLUS_INCLUDE_PATH" in repository_ctx.os.environ:
        result = []
        for p in repository_ctx.os.environ["CPLUS_INCLUDE_PATH"].split(":"):
            p = escape_string(str(repository_ctx.path(p)))  # Normalize the path
            result.append("-I" + p)
        return result
    else:
        return []

_INC_DIR_MARKER_BEGIN = "#include <...>"

# OSX add " (framework directory)" at the end of line, strip it.
_OSX_FRAMEWORK_SUFFIX = " (framework directory)"
_OSX_FRAMEWORK_SUFFIX_LEN = len(_OSX_FRAMEWORK_SUFFIX)

def _cxx_inc_convert(path):
    """Convert path returned by cc -E xc++ in a complete path. Doesn't %-escape the path!"""
    path = path.strip()
    if path.endswith(_OSX_FRAMEWORK_SUFFIX):
        path = path[:-_OSX_FRAMEWORK_SUFFIX_LEN].strip()
    return path

def _get_cxx_include_directories(repository_ctx, cc, lang_flag, additional_flags = []):
    """Compute the list of C++ include directories."""
    result = repository_ctx.execute([cc, "-E", lang_flag, "-", "-v"] + additional_flags)
    index1 = result.stderr.find(_INC_DIR_MARKER_BEGIN)
    if index1 == -1:
        return []
    index1 = result.stderr.find("\n", index1)
    if index1 == -1:
        return []
    index2 = result.stderr.rfind("\n ")
    if index2 == -1 or index2 < index1:
        return []
    index2 = result.stderr.find("\n", index2 + 1)
    if index2 == -1:
        inc_dirs = result.stderr[index1 + 1:]
    else:
        inc_dirs = result.stderr[index1 + 1:index2].strip()

    inc_directories = [
        _prepare_include_path(repository_ctx, _cxx_inc_convert(p))
        for p in inc_dirs.split("\n")
    ]

    if _is_compiler_option_supported(repository_ctx, cc, "-print-resource-dir"):
        resource_dir = repository_ctx.execute(
            [cc, "-print-resource-dir"],
        ).stdout.strip() + "/share"
        inc_directories.append(_prepare_include_path(repository_ctx, resource_dir))

    return inc_directories

def _is_compiler_option_supported(repository_ctx, cc, option):
    """Checks that `option` is supported by the C compiler. Doesn't %-escape the option."""
    result = repository_ctx.execute([
        cc,
        option,
        "-o",
        "/dev/null",
        "-c",
        str(repository_ctx.path("tools/cpp/empty.cc")),
    ])
    return result.stderr.find(option) == -1

def _is_linker_option_supported(repository_ctx, cc, option, pattern):
    """Checks that `option` is supported by the C linker. Doesn't %-escape the option."""
    result = repository_ctx.execute([
        cc,
        option,
        "-o",
        "/dev/null",
        str(repository_ctx.path("tools/cpp/empty.cc")),
    ])
    return result.stderr.find(pattern) == -1

def _find_linker_path(repository_ctx, cc, linker, is_clang):
    """Checks if a given linker is supported by the C compiler.

    Args:
      repository_ctx: repository_ctx.
      cc: path to the C compiler.
      linker: linker to find
      is_clang: whether the compiler is known to be clang

    Returns:
      String to put as value to -fuse-ld= flag, or None if linker couldn't be found.
    """
    result = repository_ctx.execute([
        cc,
        str(repository_ctx.path("tools/cpp/empty.cc")),
        "-o",
        "/dev/null",
        # Some macOS clang versions don't fail when setting -fuse-ld=gold, adding
        # these lines to force it to. This also means that we will not detect
        # gold when only a very old (year 2010 and older) is present.
        "-Wl,--start-lib",
        "-Wl,--end-lib",
        "-fuse-ld=" + linker,
        "-v",
    ])
    if result.return_code != 0:
        return None

    if not is_clang:
        return linker

    for line in result.stderr.splitlines():
        if line.find(linker) == -1:
            continue
        for flag in line.split(" "):
            if flag.find(linker) == -1:
                continue

            # flag looks like "/usr/lib/ld.gold".
            return flag.strip(" \"'")
    auto_configure_warning(
        "CC with -fuse-ld=" + linker + " returned 0, but its -v output " +
        "didn't contain '" + linker + "', falling back to the default linker.",
    )
    return None

def _add_compiler_option_if_supported(repository_ctx, cc, option):
    """Returns `[option]` if supported, `[]` otherwise. Doesn't %-escape the option."""
    return [option] if _is_compiler_option_supported(repository_ctx, cc, option) else []

def _add_linker_option_if_supported(repository_ctx, cc, option, pattern):
    """Returns `[option]` if supported, `[]` otherwise. Doesn't %-escape the option."""
    return [option] if _is_linker_option_supported(repository_ctx, cc, option, pattern) else []

def _get_no_canonical_prefixes_opt(repository_ctx, cc):
    # If the compiler sometimes rewrites paths in the .d files without symlinks
    # (ie when they're shorter), it confuses Bazel's logic for verifying all
    # #included header files are listed as inputs to the action.

    # The '-fno-canonical-system-headers' should be enough, but clang does not
    # support it, so we also try '-no-canonical-prefixes' if first option does
    # not work.
    opt = _add_compiler_option_if_supported(
        repository_ctx,
        cc,
        "-fno-canonical-system-headers",
    )
    if len(opt) == 0:
        return _add_compiler_option_if_supported(
            repository_ctx,
            cc,
            "-no-canonical-prefixes",
        )
    return opt

def get_env(repository_ctx):
    """Convert the environment in a list of export if in Homebrew. Doesn't %-escape the result!"""
    env = repository_ctx.os.environ
    if "HOMEBREW_RUBY_PATH" in env:
        return "\n".join([
            "export %s='%s'" % (k, env[k].replace("'", "'\\''"))
            for k in env
            if k != "_" and k.find(".") == -1
        ])
    else:
        return ""

def _coverage_flags(repository_ctx, darwin):
    use_llvm_cov = "1" == get_env_var(
        repository_ctx,
        "BAZEL_USE_LLVM_NATIVE_COVERAGE",
        default = "0",
        enable_warning = False,
    )
    if darwin or use_llvm_cov:
        compile_flags = '"-fprofile-instr-generate",  "-fcoverage-mapping"'
        link_flags = '"-fprofile-instr-generate"'
    else:
        # gcc requires --coverage being passed for compilation and linking
        # https://gcc.gnu.org/onlinedocs/gcc/Instrumentation-Options.html#Instrumentation-Options
        compile_flags = '"--coverage"'
        link_flags = '"--coverage"'
    return compile_flags, link_flags

def _is_clang(repository_ctx, cc):
    return "clang" in repository_ctx.execute([cc, "-v"]).stderr

def _find_generic(repository_ctx, name, env_name, overriden_tools, warn = False, silent = False):
    """Find a generic C++ toolchain tool. Doesn't %-escape the result."""

    if name in overriden_tools:
        return overriden_tools[name]

    result = name
    env_value = repository_ctx.os.environ.get(env_name)
    env_value_with_paren = ""
    if env_value != None:
        env_value = env_value.strip()
        if env_value:
            result = env_value
            env_value_with_paren = " (%s)" % env_value
    if result.startswith("/"):
        # Absolute path, maybe we should make this suported by our which function.
        return result
    result = repository_ctx.which(result)
    if result == None:
        msg = ("Cannot find %s or %s%s; either correct your path or set the %s" +
               " environment variable") % (name, env_name, env_value_with_paren, env_name)
        if warn:
            if not silent:
                auto_configure_warning(msg)
        else:
            auto_configure_fail(msg)
    return result

def find_cc(repository_ctx, overriden_tools):
    return _find_generic(repository_ctx, "gcc", "CC", overriden_tools)

def configure_unix_toolchain(repository_ctx, cpu_value, overriden_tools):
    """Configure C++ toolchain on Unix platforms."""
    paths = resolve_labels(repository_ctx, [
        "@bazel_tools//tools/cpp:BUILD.tpl",
        "@bazel_tools//tools/cpp:generate_system_module_map.sh",
        "@bazel_tools//tools/cpp:armeabi_cc_toolchain_config.bzl",
        "@bazel_tools//tools/cpp:unix_cc_toolchain_config.bzl",
        "@bazel_tools//tools/cpp:linux_cc_wrapper.sh.tpl",
        "@bazel_tools//tools/cpp:osx_cc_wrapper.sh.tpl",
    ])

    repository_ctx.symlink(
        paths["@bazel_tools//tools/cpp:unix_cc_toolchain_config.bzl"],
        "cc_toolchain_config.bzl",
    )

    repository_ctx.symlink(
        paths["@bazel_tools//tools/cpp:armeabi_cc_toolchain_config.bzl"],
        "armeabi_cc_toolchain_config.bzl",
    )

    repository_ctx.file("tools/cpp/empty.cc", "int main() {}")
    darwin = cpu_value == "darwin"

    cc = _find_generic(repository_ctx, "gcc", "CC", overriden_tools)
    is_clang = _is_clang(repository_ctx, cc)
    overriden_tools = dict(overriden_tools)
    overriden_tools["gcc"] = cc
    overriden_tools["gcov"] = _find_generic(
        repository_ctx,
        "gcov",
        "GCOV",
        overriden_tools,
        warn = True,
        silent = True,
    )
    overriden_tools["llvm-cov"] = _find_generic(
        repository_ctx,
        "llvm-cov",
        "BAZEL_LLVM_COV",
        overriden_tools,
        warn = True,
        silent = True,
    )
    overriden_tools["ar"] = _find_generic(
        repository_ctx,
        "ar",
        "AR",
        overriden_tools,
        warn = True,
        silent = True,
    )
    if darwin:
        overriden_tools["gcc"] = "cc_wrapper.sh"
        overriden_tools["ar"] = "/usr/bin/libtool"
    auto_configure_warning_maybe(repository_ctx, "CC used: " + str(cc))
    tool_paths = _get_tool_paths(repository_ctx, overriden_tools)
    cc_toolchain_identifier = escape_string(get_env_var(
        repository_ctx,
        "CC_TOOLCHAIN_NAME",
        "local",
        False,
    ))

    cc_wrapper_src = (
        "@bazel_tools//tools/cpp:osx_cc_wrapper.sh.tpl" if darwin else "@bazel_tools//tools/cpp:linux_cc_wrapper.sh.tpl"
    )
    repository_ctx.template(
        "cc_wrapper.sh",
        paths[cc_wrapper_src],
        {
            "%{cc}": escape_string(str(cc)),
            "%{env}": escape_string(get_env(repository_ctx)),
        },
    )

    cxx_opts = split_escaped(get_env_var(
        repository_ctx,
        "BAZEL_CXXOPTS",
        "-std=c++0x",
        False,
    ), ":")

    bazel_linkopts = "-lstdc++:-lm"
    bazel_linklibs = ""
    if repository_ctx.flag_enabled("incompatible_linkopts_to_linklibs"):
        bazel_linkopts, bazel_linklibs = bazel_linklibs, bazel_linkopts

    link_opts = split_escaped(get_env_var(
        repository_ctx,
        "BAZEL_LINKOPTS",
        bazel_linkopts,
        False,
    ), ":")
    link_libs = split_escaped(get_env_var(
        repository_ctx,
        "BAZEL_LINKLIBS",
        bazel_linklibs,
        False,
    ), ":")
    gold_or_lld_linker_path = (
        _find_linker_path(repository_ctx, cc, "lld", is_clang) or
        _find_linker_path(repository_ctx, cc, "gold", is_clang)
    )
    cc_path = repository_ctx.path(cc)
    if not str(cc_path).startswith(str(repository_ctx.path(".")) + "/"):
        # cc is outside the repository, set -B
        bin_search_flag = ["-B" + escape_string(str(cc_path.dirname))]
    else:
        # cc is inside the repository, don't set -B.
        bin_search_flag = []

    coverage_compile_flags, coverage_link_flags = _coverage_flags(repository_ctx, darwin)
    builtin_include_directories = _uniq(
        _get_cxx_include_directories(repository_ctx, cc, "-xc") +
        _get_cxx_include_directories(repository_ctx, cc, "-xc++", cxx_opts) +
        _get_cxx_include_directories(
            repository_ctx,
            cc,
            "-xc",
            _get_no_canonical_prefixes_opt(repository_ctx, cc),
        ) +
        _get_cxx_include_directories(
            repository_ctx,
            cc,
            "-xc++",
            cxx_opts + _get_no_canonical_prefixes_opt(repository_ctx, cc),
        ),
    )

    if is_clang:
        repository_ctx.file("module.modulemap", _generate_system_module_map(
            repository_ctx,
            builtin_include_directories,
            paths["@bazel_tools//tools/cpp:generate_system_module_map.sh"],
        ))

    write_builtin_include_directory_paths(repository_ctx, cc, builtin_include_directories)
    repository_ctx.template(
        "BUILD",
        paths["@bazel_tools//tools/cpp:BUILD.tpl"],
        {
            "%{cc_toolchain_identifier}": cc_toolchain_identifier,
            "%{name}": cpu_value,
            "%{modulemap}": ("\":module.modulemap\"" if is_clang else "None"),
            "%{cc_compiler_deps}": get_starlark_list([":builtin_include_directory_paths"] + (
                [":cc_wrapper"] if darwin else []
            )),
            "%{compiler}": escape_string(get_env_var(
                repository_ctx,
                "BAZEL_COMPILER",
                "clang" if is_clang else "compiler",
                False,
            )),
            "%{abi_version}": escape_string(get_env_var(
                repository_ctx,
                "ABI_VERSION",
                "local",
                False,
            )),
            "%{abi_libc_version}": escape_string(get_env_var(
                repository_ctx,
                "ABI_LIBC_VERSION",
                "local",
                False,
            )),
            "%{host_system_name}": escape_string(get_env_var(
                repository_ctx,
                "BAZEL_HOST_SYSTEM",
                "local",
                False,
            )),
            "%{target_libc}": "macosx" if darwin else escape_string(get_env_var(
                repository_ctx,
                "BAZEL_TARGET_LIBC",
                "local",
                False,
            )),
            "%{target_cpu}": escape_string(get_env_var(
                repository_ctx,
                "BAZEL_TARGET_CPU",
                cpu_value,
                False,
            )),
            "%{target_system_name}": escape_string(get_env_var(
                repository_ctx,
                "BAZEL_TARGET_SYSTEM",
                "local",
                False,
            )),
            "%{tool_paths}": ",\n        ".join(
                ['"%s": "%s"' % (k, v) for k, v in tool_paths.items()],
            ),
            "%{cxx_builtin_include_directories}": get_starlark_list(builtin_include_directories),
            "%{compile_flags}": get_starlark_list(
                [
                    # Security hardening requires optimization.
                    # We need to undef it as some distributions now have it enabled by default.
                    "-U_FORTIFY_SOURCE",
                    "-fstack-protector",
                    # All warnings are enabled. Maybe enable -Werror as well?
                    "-Wall",
                    # Enable a few more warnings that aren't part of -Wall.
                ] + ((
                    _add_compiler_option_if_supported(repository_ctx, cc, "-Wthread-safety") +
                    _add_compiler_option_if_supported(repository_ctx, cc, "-Wself-assign")
                )) + (
                    # Disable problematic warnings.
                    _add_compiler_option_if_supported(repository_ctx, cc, "-Wunused-but-set-parameter") +
                    # has false positives
                    _add_compiler_option_if_supported(repository_ctx, cc, "-Wno-free-nonheap-object") +
                    # Enable coloring even if there's no attached terminal. Bazel removes the
                    # escape sequences if --nocolor is specified.
                    _add_compiler_option_if_supported(repository_ctx, cc, "-fcolor-diagnostics")
                ) + [
                    # Keep stack frames for debugging, even in opt mode.
                    "-fno-omit-frame-pointer",
                ],
            ),
            "%{cxx_flags}": get_starlark_list(cxx_opts + _escaped_cplus_include_paths(repository_ctx)),
            "%{link_flags}": get_starlark_list((
                ["-fuse-ld=" + gold_or_lld_linker_path] if gold_or_lld_linker_path else []
            ) + _add_linker_option_if_supported(
                repository_ctx,
                cc,
                "-Wl,-no-as-needed",
                "-no-as-needed",
            ) + _add_linker_option_if_supported(
                repository_ctx,
                cc,
                "-Wl,-z,relro,-z,now",
                "-z",
            ) + (
                [
                    "-undefined",
                    "dynamic_lookup",
                    "-headerpad_max_install_names",
                ] if darwin else bin_search_flag + [
                    # Gold linker only? Can we enable this by default?
                    # "-Wl,--warn-execstack",
                    # "-Wl,--detect-odr-violations"
                ] + _add_compiler_option_if_supported(
                    # Have gcc return the exit code from ld.
                    repository_ctx,
                    cc,
                    "-pass-exit-codes",
                )
            ) + link_opts),
            "%{link_libs}": get_starlark_list(link_libs),
            "%{opt_compile_flags}": get_starlark_list(
                [
                    # No debug symbols.
                    # Maybe we should enable https://gcc.gnu.org/wiki/DebugFission for opt or
                    # even generally? However, that can't happen here, as it requires special
                    # handling in Bazel.
                    "-g0",

                    # Conservative choice for -O
                    # -O3 can increase binary size and even slow down the resulting binaries.
                    # Profile first and / or use FDO if you need better performance than this.
                    "-O2",

                    # Security hardening on by default.
                    # Conservative choice; -D_FORTIFY_SOURCE=2 may be unsafe in some cases.
                    "-D_FORTIFY_SOURCE=1",

                    # Disable assertions
                    "-DNDEBUG",

                    # Removal of unused code and data at link time (can this increase binary
                    # size in some cases?).
                    "-ffunction-sections",
                    "-fdata-sections",
                ],
            ),
            "%{opt_link_flags}": get_starlark_list(
                [] if darwin else _add_linker_option_if_supported(
                    repository_ctx,
                    cc,
                    "-Wl,--gc-sections",
                    "-gc-sections",
                ),
            ),
            "%{unfiltered_compile_flags}": get_starlark_list(
                _get_no_canonical_prefixes_opt(repository_ctx, cc) + [
                    # Make C++ compilation deterministic. Use linkstamping instead of these
                    # compiler symbols.
                    "-Wno-builtin-macro-redefined",
                    "-D__DATE__=\\\"redacted\\\"",
                    "-D__TIMESTAMP__=\\\"redacted\\\"",
                    "-D__TIME__=\\\"redacted\\\"",
                ],
            ),
            "%{dbg_compile_flags}": get_starlark_list(["-g"]),
            "%{coverage_compile_flags}": coverage_compile_flags,
            "%{coverage_link_flags}": coverage_link_flags,
            "%{supports_start_end_lib}": "True" if gold_or_lld_linker_path else "False",
        },
    )
