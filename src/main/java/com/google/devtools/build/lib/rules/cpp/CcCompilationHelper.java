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

package com.google.devtools.build.lib.rules.cpp;

import static java.util.stream.Collectors.toCollection;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionRegistry;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.PerLabelOptions;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.VariablesExtension;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.HeadersCheckingMode;
import com.google.devtools.build.lib.skylarkbuildapi.cpp.CompilationInfoApi;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A class to create C/C++ compile actions in a way that is consistent with cc_library. Rules that
 * generate source files and emulate cc_library on top of that should use this class instead of the
 * lower-level APIs in CppHelper and CppCompileActionBuilder.
 *
 * <p>Rules that want to use this class are required to have implicit dependencies on the toolchain,
 * the STL, and so on. Optionally, they can also have copts, and malloc attributes, but note that
 * these require explicit calls to the corresponding setter methods.
 */
public final class CcCompilationHelper {
  /** Similar to {@code OutputGroupInfo.HIDDEN_TOP_LEVEL}, but specific to header token files. */
  public static final String HIDDEN_HEADER_TOKENS =
      OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX
          + "hidden_header_tokens"
          + OutputGroupInfo.INTERNAL_SUFFIX;

  /**
   * Configures a compile action builder by setting up command line options and auxiliary inputs
   * according to the FDO configuration. This method does nothing If FDO is disabled.
   */
  @ThreadSafe
  public static void configureFdoBuildVariables(
      ImmutableMap.Builder<String, String> variablesBuilder,
      CppCompileActionBuilder builder,
      FeatureConfiguration featureConfiguration,
      FdoContext fdoContext,
      String fdoInstrument,
      CppConfiguration cppConfiguration) {
    if (featureConfiguration.isEnabled(CppRuleClasses.FDO_INSTRUMENT)) {
      variablesBuilder.put(
          CompileBuildVariables.FDO_INSTRUMENT_PATH.getVariableName(), fdoInstrument);
    }

    // FDO is disabled -> do nothing.
    Preconditions.checkNotNull(fdoContext);
    if (!fdoContext.hasArtifacts(cppConfiguration)) {
      return;
    }

    if (fdoContext.getPrefetchHintsArtifact() != null) {
      variablesBuilder.put(
          CompileBuildVariables.FDO_PREFETCH_HINTS_PATH.getVariableName(),
          fdoContext.getPrefetchHintsArtifact().getExecPathString());
    }

    Iterable<Artifact> auxiliaryInputs = getAuxiliaryFdoInputs(fdoContext);
    builder.addMandatoryInputs(auxiliaryInputs);

    FdoContext.BranchFdoProfile branchFdoProfile = fdoContext.getBranchFdoProfile();
    // Optimization phase
    if (branchFdoProfile != null) {
      if (!Iterables.isEmpty(auxiliaryInputs)) {
        if (featureConfiguration.isEnabled(CppRuleClasses.AUTOFDO)
            || featureConfiguration.isEnabled(CppRuleClasses.XBINARYFDO)) {
          variablesBuilder.put(
              CompileBuildVariables.FDO_PROFILE_PATH.getVariableName(),
              branchFdoProfile.getProfileArtifact().getExecPathString());
        }
        if (featureConfiguration.isEnabled(CppRuleClasses.FDO_OPTIMIZE)) {
          if (branchFdoProfile.isLlvmFdo()) {
            variablesBuilder.put(
                CompileBuildVariables.FDO_PROFILE_PATH.getVariableName(),
                branchFdoProfile.getProfileArtifact().getExecPathString());
          }
        }
      }
    }
  }

  /** Returns the auxiliary files that need to be added to the {@link CppCompileAction}. */
  private static Iterable<Artifact> getAuxiliaryFdoInputs(FdoContext fdoContext) {
    ImmutableSet.Builder<Artifact> auxiliaryInputs = ImmutableSet.builder();

    if (fdoContext.getPrefetchHintsArtifact() != null) {
      auxiliaryInputs.add(fdoContext.getPrefetchHintsArtifact());
    }
    FdoContext.BranchFdoProfile branchFdoProfile = fdoContext.getBranchFdoProfile();
    // If --fdo_optimize was not specified, we don't have any additional inputs.
    if (branchFdoProfile != null) {
      auxiliaryInputs.add(branchFdoProfile.getProfileArtifact());
    }

    return auxiliaryInputs.build();
  }

  /**
   * A group of source file types and action names for builds controlled by CcCompilationHelper.
   * Determines what file types CcCompilationHelper considers sources and what action configs are
   * configured in the CROSSTOOL.
   */
  public enum SourceCategory {
    CC(
        FileTypeSet.of(
            CppFileTypes.CPP_SOURCE,
            CppFileTypes.CPP_HEADER,
            CppFileTypes.C_SOURCE,
            CppFileTypes.ASSEMBLER,
            CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR,
            CppFileTypes.CLIF_INPUT_PROTO)),
    CC_AND_OBJC(
        FileTypeSet.of(
            CppFileTypes.CPP_SOURCE,
            CppFileTypes.CPP_HEADER,
            CppFileTypes.OBJC_SOURCE,
            CppFileTypes.OBJCPP_SOURCE,
            CppFileTypes.C_SOURCE,
            CppFileTypes.ASSEMBLER,
            CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR));

    private final FileTypeSet sourceTypeSet;

    SourceCategory(FileTypeSet sourceTypeSet) {
      this.sourceTypeSet = sourceTypeSet;
    }

    /** Returns the set of file types that are valid for this category. */
    public FileTypeSet getSourceTypes() {
      return sourceTypeSet;
    }
  }

  /** Function for extracting module maps from CppCompilationDependencies. */
  private static final Function<CcCompilationContext, CppModuleMap> CPP_DEPS_TO_MODULES =
      ccCompilationContext -> ccCompilationContext.getCppModuleMap();

  /**
   * Contains the providers as well as the {@code CcCompilationOutputs} and the {@code
   * CcCompilationContext}.
   */
  // TODO(plf): Rename so that it's not confused with CcCompilationContext and also consider
  // merging
  // this class with {@code CcCompilationOutputs}.
  public static final class CompilationInfo implements CompilationInfoApi {
    private final CcCompilationContext ccCompilationContext;
    private final CcCompilationOutputs compilationOutputs;

    private CompilationInfo(
        CcCompilationContext ccCompilationContext,
        CcCompilationOutputs compilationOutputs) {
      this.ccCompilationContext = ccCompilationContext;
      this.compilationOutputs = compilationOutputs;
    }

    @Override
    public CcCompilationOutputs getCcCompilationOutputs() {
      return compilationOutputs;
    }

    @Override
    public CcCompilationContext getCcCompilationContext() {
      return ccCompilationContext;
    }
  }

  private final CppSemantics semantics;
  private final BuildConfiguration configuration;
  private final CppConfiguration cppConfiguration;

  private final List<Artifact> publicHeaders = new ArrayList<>();
  private final List<Artifact> nonModuleMapHeaders = new ArrayList<>();
  private final List<Artifact> publicTextualHeaders = new ArrayList<>();
  private final List<Artifact> privateHeaders = new ArrayList<>();
  private final List<Artifact> additionalInputs = new ArrayList<>();
  private final List<Artifact> additionalCompilationInputs = new ArrayList<>();
  private final List<Artifact> additionalIncludeScanningRoots = new ArrayList<>();
  private final List<PathFragment> additionalExportedHeaders = new ArrayList<>();
  private final List<CppModuleMap> additionalCppModuleMaps = new ArrayList<>();
  private final LinkedHashMap<Artifact, CppSource> compilationUnitSources = new LinkedHashMap<>();
  private ImmutableList<String> copts = ImmutableList.of();
  private CoptsFilter coptsFilter = CoptsFilter.alwaysPasses();
  private final Set<String> defines = new LinkedHashSet<>();
  private final List<CcCompilationContext> ccCompilationContexts = new ArrayList<>();
  private Set<PathFragment> looseIncludeDirs = ImmutableSet.of();
  private final List<PathFragment> systemIncludeDirs = new ArrayList<>();
  private final List<PathFragment> quoteIncludeDirs = new ArrayList<>();
  private final List<PathFragment> includeDirs = new ArrayList<>();

  private HeadersCheckingMode headersCheckingMode = HeadersCheckingMode.LOOSE;
  private boolean fake;

  private final SourceCategory sourceCategory;
  private final List<VariablesExtension> variablesExtensions = new ArrayList<>();
  @Nullable private CppModuleMap cppModuleMap;
  private boolean propagateModuleMapToCompileAction = true;

  private final FeatureConfiguration featureConfiguration;
  private final CcToolchainProvider ccToolchain;
  private final FdoContext fdoContext;
  private boolean generateModuleMap = true;
  private String purpose = null;
  private boolean generateNoPicAction;
  private boolean generatePicAction;
  private boolean isCodeCoverageEnabled = true;
  private String stripIncludePrefix = null;
  private String includePrefix = null;

  // TODO(plf): Pull out of class.
  private CcCompilationContext ccCompilationContext;

  private final RuleErrorConsumer ruleErrorConsumer;
  private final ActionRegistry actionRegistry;
  private final ActionConstructionContext actionConstructionContext;
  private final Label label;
  @Nullable private final Artifact grepIncludes;

  /** Creates a CcCompilationHelper that outputs artifacts in a given configuration. */
  public CcCompilationHelper(
      ActionRegistry actionRegistry,
      ActionConstructionContext actionConstructionContext,
      Label label,
      @Nullable Artifact grepIncludes,
      CppSemantics semantics,
      FeatureConfiguration featureConfiguration,
      SourceCategory sourceCategory,
      CcToolchainProvider ccToolchain,
      FdoContext fdoContext,
      BuildConfiguration buildConfiguration) {
    this.semantics = Preconditions.checkNotNull(semantics);
    this.featureConfiguration = Preconditions.checkNotNull(featureConfiguration);
    this.sourceCategory = Preconditions.checkNotNull(sourceCategory);
    this.ccToolchain = Preconditions.checkNotNull(ccToolchain);
    this.fdoContext = Preconditions.checkNotNull(fdoContext);
    this.actionConstructionContext = Preconditions.checkNotNull(actionConstructionContext);
    this.configuration = buildConfiguration;
    this.cppConfiguration = configuration.getFragment(CppConfiguration.class);
    setGenerateNoPicAction(
        !ccToolchain.usePicForDynamicLibraries(cppConfiguration, featureConfiguration)
            || !CppHelper.usePicForBinaries(ccToolchain, cppConfiguration, featureConfiguration));
    setGeneratePicAction(
        ccToolchain.usePicForDynamicLibraries(cppConfiguration, featureConfiguration)
            || CppHelper.usePicForBinaries(ccToolchain, cppConfiguration, featureConfiguration));
    this.ruleErrorConsumer = actionConstructionContext.getRuleErrorConsumer();
    this.actionRegistry = Preconditions.checkNotNull(actionRegistry);
    this.label = Preconditions.checkNotNull(label);
    this.grepIncludes = grepIncludes;
  }

  /** Creates a CcCompilationHelper for cpp source files. */
  public CcCompilationHelper(
      ActionRegistry actionRegistry,
      ActionConstructionContext actionConstructionContext,
      Label label,
      @Nullable Artifact grepIncludes,
      CppSemantics semantics,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchain,
      FdoContext fdoContext) {
    this(
        actionRegistry,
        actionConstructionContext,
        label,
        grepIncludes,
        semantics,
        featureConfiguration,
        SourceCategory.CC,
        ccToolchain,
        fdoContext,
        actionConstructionContext.getConfiguration());
  }

  /** Sets fields that overlap for cc_library and cc_binary rules. */
  public CcCompilationHelper fromCommon(CcCommon common, ImmutableList<String> additionalCopts) {
    Preconditions.checkNotNull(additionalCopts);

    setCopts(Iterables.concat(common.getCopts(), additionalCopts));
    addDefines(common.getDefines());
    setLooseIncludeDirs(common.getLooseIncludeDirs());
    addSystemIncludeDirs(common.getSystemIncludeDirs());
    setCoptsFilter(common.getCoptsFilter());
    return this;
  }

  /**
   * Adds {@code headers} as public header files. These files will be made visible to dependent
   * rules. They may be parsed/preprocessed or compiled into a header module depending on the
   * configuration.
   */
  public CcCompilationHelper addPublicHeaders(Collection<Artifact> headers) {
    for (Artifact header : headers) {
      addHeader(header, label);
    }
    return this;
  }

  /**
   * Adds {@code headers} as public header files. These files will be made visible to dependent
   * rules. They may be parsed/preprocessed or compiled into a header module depending on the
   * configuration.
   */
  public CcCompilationHelper addPublicHeaders(Artifact... headers) {
    addPublicHeaders(Arrays.asList(headers));
    return this;
  }

  /**
   * Adds {@code headers} as public header files. These files will be made visible to dependent
   * rules. They may be parsed/preprocessed or compiled into a header module depending on the
   * configuration.
   */
  public CcCompilationHelper addPublicHeaders(Iterable<Pair<Artifact, Label>> headers) {
    for (Pair<Artifact, Label> header : headers) {
      addHeader(header.first, header.second);
    }
    return this;
  }

  /**
   * Add the corresponding files as public header files, i.e., these files will not be compiled, but
   * are made visible as includes to dependent rules in module maps.
   */
  public CcCompilationHelper addAdditionalExportedHeaders(
      Iterable<PathFragment> additionalExportedHeaders) {
    Iterables.addAll(this.additionalExportedHeaders, additionalExportedHeaders);
    return this;
  }

  /**
   * Add the corresponding files as public textual header files. These files will not be compiled
   * into a target's header module, but will be made visible as textual includes to dependent rules.
   */
  public CcCompilationHelper addPublicTextualHeaders(Iterable<Artifact> textualHeaders) {
    Iterables.addAll(this.publicTextualHeaders, textualHeaders);
    for (Artifact header : textualHeaders) {
      this.additionalExportedHeaders.add(header.getExecPath());
    }
    return this;
  }

  public CcCompilationHelper addPrivateHeaders(Collection<Artifact> privateHeaders) {
    for (Artifact privateHeader : privateHeaders) {
      addPrivateHeader(privateHeader, label);
    }
    return this;
  }

  public CcCompilationHelper addPrivateHeaders(Iterable<Pair<Artifact, Label>> privateHeaders) {
    for (Pair<Artifact, Label> headerLabelPair : privateHeaders) {
      addPrivateHeader(headerLabelPair.first, headerLabelPair.second);
    }
    return this;
  }

  private CcCompilationHelper addPrivateHeader(Artifact privateHeader, Label label) {
    boolean isHeader = CppFileTypes.CPP_HEADER.matches(privateHeader.getExecPath());
    boolean isTextualInclude =
        CppFileTypes.CPP_TEXTUAL_INCLUDE.matches(privateHeader.getExecPath());
    Preconditions.checkState(isHeader || isTextualInclude);

    if (ccToolchain.shouldProcessHeaders(featureConfiguration, cppConfiguration)
        && !isTextualInclude) {
      compilationUnitSources.put(
          privateHeader, CppSource.create(privateHeader, label, CppSource.Type.HEADER));
    }
    
    this.privateHeaders.add(privateHeader);
    return this;
  }

  /**
   * Add the corresponding files as source files. These may also be header files, in which case they
   * will not be compiled, but also not made visible as includes to dependent rules. The given build
   * variables will be added to those used for compiling this source.
   */
  public CcCompilationHelper addSources(Collection<Artifact> sources) {
    for (Artifact source : sources) {
      addSource(source, label);
    }
    return this;
  }

  /**
   * Add the corresponding files as source files. These may also be header files, in which case they
   * will not be compiled, but also not made visible as includes to dependent rules.
   */
  public CcCompilationHelper addSources(Iterable<Pair<Artifact, Label>> sources) {
    for (Pair<Artifact, Label> source : sources) {
      addSource(source.first, source.second);
    }
    return this;
  }

  /**
   * Add the corresponding files as source files. These may also be header files, in which case they
   * will not be compiled, but also not made visible as includes to dependent rules.
   */
  public CcCompilationHelper addSources(Artifact... sources) {
    return addSources(Arrays.asList(sources));
  }

  /** Add the corresponding files as non-header, non-source input files. */
  public CcCompilationHelper addAdditionalInputs(Collection<Artifact> inputs) {
    Iterables.addAll(additionalInputs, inputs);
    return this;
  }

  /**
   * Adds a header to {@code publicHeaders} and in case header processing is switched on for the
   * file type also to compilationUnitSources.
   */
  private void addHeader(Artifact header, Label label) {
    // We assume TreeArtifacts passed in are directories containing proper headers.
    boolean isHeader =
        CppFileTypes.CPP_HEADER.matches(header.getExecPath()) || header.isTreeArtifact();
    boolean isTextualInclude = CppFileTypes.CPP_TEXTUAL_INCLUDE.matches(header.getExecPath());
    publicHeaders.add(header);
    if (isTextualInclude
        || !isHeader
        || !ccToolchain.shouldProcessHeaders(featureConfiguration, cppConfiguration)) {
      return;
    }

    compilationUnitSources.put(header, CppSource.create(header, label, CppSource.Type.HEADER));
  }

  /** Adds a header to {@code publicHeaders}, but not to this target's module map. */
  public CcCompilationHelper addNonModuleMapHeader(Artifact header) {
    Preconditions.checkNotNull(header);
    nonModuleMapHeaders.add(header);
    return this;
  }

  /**
   * Adds a source to {@code compilationUnitSources} if it is a compiled file type (including
   * parsed/preprocessed header) and to {@code privateHeaders} if it is a header.
   */
  private void addSource(Artifact source, Label label) {
    Preconditions.checkNotNull(featureConfiguration);
    Preconditions.checkState(!CppFileTypes.CPP_HEADER.matches(source.getExecPath()));
    // We assume TreeArtifacts passed in are directories containing proper sources for compilation.
    if (!sourceCategory.getSourceTypes().matches(source.getExecPathString())
        && !source.isTreeArtifact()) {
      // TODO(plf): If it's a non-source file we ignore it. This is only the case for precompiled
      // files which should be forbidden in srcs of cc_library|binary and instead be migrated to
      // cc_import rules.
      return;
    }

    boolean isClifInputProto = CppFileTypes.CLIF_INPUT_PROTO.matches(source.getExecPathString());
    CppSource.Type type;
    if (isClifInputProto) {
      type = CppSource.Type.CLIF_INPUT_PROTO;
    } else {
      type = CppSource.Type.SOURCE;
    }
    compilationUnitSources.put(source, CppSource.create(source, label, type));
  }

  /**
   * Returns the compilation unit sources. That includes all compiled source files as well as
   * headers that will be parsed or preprocessed. Each source file contains the label it arises from
   * in the build graph as well as {@code FeatureConfiguration} that should be used during its
   * compilation.
   */
  public ImmutableSet<CppSource> getCompilationUnitSources() {
    return ImmutableSet.copyOf(this.compilationUnitSources.values());
  }

  public CcCompilationHelper setCopts(Iterable<String> copts) {
    this.copts = ImmutableList.copyOf(copts);
    return this;
  }

  /** Sets a pattern that is used to filter copts; set to {@code null} for no filtering. */
  private void setCoptsFilter(CoptsFilter coptsFilter) {
    this.coptsFilter = Preconditions.checkNotNull(coptsFilter);
  }

  /** Adds the given defines to the compiler command line. */
  public CcCompilationHelper addDefines(Iterable<String> defines) {
    Iterables.addAll(this.defines, defines);
    return this;
  }

  /** For adding CC compilation infos that affect compilation, e.g: from dependencies. */
  public CcCompilationHelper addCcCompilationContexts(
      Iterable<CcCompilationContext> ccCompilationContexts) {
    Iterables.addAll(this.ccCompilationContexts, Preconditions.checkNotNull(ccCompilationContexts));
    return this;
  }

  /**
   * Sets the given directories to by loose include directories that are only allowed to be
   * referenced when headers checking is {@link HeadersCheckingMode#LOOSE}.
   */
  private void setLooseIncludeDirs(Set<PathFragment> looseIncludeDirs) {
    this.looseIncludeDirs = looseIncludeDirs;
  }

  /**
   * Adds the given directories to the system include directories (they are passed with {@code
   * "-isystem"} to the compiler); these are also passed to dependent rules.
   */
  public CcCompilationHelper addSystemIncludeDirs(Iterable<PathFragment> systemIncludeDirs) {
    Iterables.addAll(this.systemIncludeDirs, systemIncludeDirs);
    return this;
  }

  /**
   * Adds the given directories to the quote include directories (they are passed with {@code
   * "-iquote"} to the compiler); these are also passed to dependent rules.
   */
  public CcCompilationHelper addQuoteIncludeDirs(Iterable<PathFragment> quoteIncludeDirs) {
    Iterables.addAll(this.quoteIncludeDirs, quoteIncludeDirs);
    return this;
  }

  /**
   * Adds the given directories to the include directories (they are passed with {@code "-I"} to the
   * compiler); these are also passed to dependent rules.
   */
  public CcCompilationHelper addIncludeDirs(Iterable<PathFragment> includeDirs) {
    Iterables.addAll(this.includeDirs, includeDirs);
    return this;
  }

  /** Adds a variableExtension to template the crosstool. */
  public CcCompilationHelper addVariableExtension(VariablesExtension variableExtension) {
    Preconditions.checkNotNull(variableExtension);
    this.variablesExtensions.add(variableExtension);
    return this;
  }

  /** Sets a module map artifact for this build. */
  public CcCompilationHelper setCppModuleMap(CppModuleMap cppModuleMap) {
    Preconditions.checkNotNull(cppModuleMap);
    this.cppModuleMap = cppModuleMap;
    return this;
  }

  /** Signals that this target's module map should not be an input to c++ compile actions. */
  public CcCompilationHelper setPropagateModuleMapToCompileAction(boolean propagatesModuleMap) {
    this.propagateModuleMapToCompileAction = propagatesModuleMap;
    return this;
  }

  /** Sets the given headers checking mode. The default is {@link HeadersCheckingMode#LOOSE}. */
  public CcCompilationHelper setHeadersCheckingMode(HeadersCheckingMode headersCheckingMode) {
    this.headersCheckingMode = Preconditions.checkNotNull(headersCheckingMode);
    return this;
  }

  /**
   * Marks the resulting code as fake, i.e., the code will not actually be compiled or linked, but
   * instead, the compile command is written to a file and added to the runfiles. This is currently
   * used for non-compilation tests. Unfortunately, the design is problematic, so please don't add
   * any further uses.
   */
  public CcCompilationHelper setFake(boolean fake) {
    this.fake = fake;
    return this;
  }

  /** Whether to generate no-PIC actions. */
  public CcCompilationHelper setGenerateNoPicAction(boolean generateNoPicAction) {
    this.generateNoPicAction = generateNoPicAction;
    return this;
  }

  /** Whether to generate PIC actions. */
  public CcCompilationHelper setGeneratePicAction(boolean generatePicAction) {
    this.generatePicAction = generatePicAction;
    return this;
  }

  /** Adds mandatory inputs for the compilation action. */
  public CcCompilationHelper addAdditionalCompilationInputs(
      Collection<Artifact> compilationMandatoryInputs) {
    this.additionalCompilationInputs.addAll(compilationMandatoryInputs);
    return this;
  }

  /** Adds additional includes to be scanned. */
  // TODO(plf): This is only needed for CLIF. Investigate whether this is strictly necessary or
  // there is a way to avoid include scanning for CLIF rules.
  public CcCompilationHelper addAditionalIncludeScanningRoots(
      Collection<Artifact> additionalIncludeScanningRoots) {
    this.additionalIncludeScanningRoots.addAll(additionalIncludeScanningRoots);
    return this;
  }

  /** Sets the include prefix to append to the public headers. */
  public CcCompilationHelper setIncludePrefix(@Nullable String includePrefix) {
    this.includePrefix = includePrefix;
    return this;
  }

  /** Sets the include prefix to remove from the public headers. */
  public CcCompilationHelper setStripIncludePrefix(@Nullable String stripIncludePrefix) {
    this.stripIncludePrefix = stripIncludePrefix;
    return this;
  }

  public CcCompilationHelper setCodeCoverageEnabled(boolean codeCoverageEnabled) {
    this.isCodeCoverageEnabled = codeCoverageEnabled;
    return this;
  }

  /**
   * Create the C++ compile actions, and the corresponding compilation related providers.
   *
   * @throws RuleErrorException
   */
  public CompilationInfo compile() throws RuleErrorException {

    if (!generatePicAction && !generateNoPicAction) {
      ruleErrorConsumer.ruleError("Either PIC or no PIC actions have to be created.");
    }

    ccCompilationContext = initializeCcCompilationContext();

    boolean compileHeaderModules = featureConfiguration.isEnabled(CppRuleClasses.HEADER_MODULES);
    Preconditions.checkState(
        !compileHeaderModules || ccCompilationContext.getCppModuleMap() != null,
        "All cc rules must support module maps.");

    // Create compile actions (both PIC and no-PIC).
    CcCompilationOutputs ccOutputs = createCcCompileActions();

    return new CompilationInfo(ccCompilationContext, ccOutputs);
  }

  public static Map<String, NestedSet<Artifact>> buildOutputGroups(
      CcCompilationOutputs ccCompilationOutputs) {
    Map<String, NestedSet<Artifact>> outputGroups = new TreeMap<>();
    outputGroups.put(OutputGroupInfo.TEMP_FILES, ccCompilationOutputs.getTemps());
    return outputGroups;
  }

  public static CppDebugFileProvider buildCppDebugFileProvider(
      CcCompilationOutputs ccCompilationOutputs, Iterable<TransitiveInfoCollection> deps) {
    DwoArtifactsCollector dwoArtifacts =
        DwoArtifactsCollector.transitiveCollector(
            ccCompilationOutputs,
            deps,
            /*generateDwo=*/ false,
            /*ltoBackendArtifactsUsePic=*/ false,
            /*ltoBackendArtifacts=*/ ImmutableList.of());
    CppDebugFileProvider cppDebugFileProvider =
        new CppDebugFileProvider(dwoArtifacts.getDwoArtifacts(), dwoArtifacts.getPicDwoArtifacts());
    return cppDebugFileProvider;
  }

  public static Map<String, NestedSet<Artifact>> buildOutputGroupsForEmittingCompileProviders(
      CcCompilationOutputs ccCompilationOutputs,
      CcCompilationContext ccCompilationContext,
      CppConfiguration cppConfiguration,
      CcToolchainProvider ccToolchain,
      FeatureConfiguration featureConfiguration,
      RuleContext ruleContext) {
    ImmutableMap.Builder<String, NestedSet<Artifact>> outputGroupsBuilder = ImmutableMap.builder();
    outputGroupsBuilder.put(OutputGroupInfo.TEMP_FILES, ccCompilationOutputs.getTemps());
    boolean processHeadersInDependencies = cppConfiguration.processHeadersInDependencies();
    boolean usePic = ccToolchain.usePicForDynamicLibraries(cppConfiguration, featureConfiguration);
    outputGroupsBuilder.put(
        OutputGroupInfo.FILES_TO_COMPILE,
        ccCompilationOutputs.getFilesToCompile(processHeadersInDependencies, usePic));
    outputGroupsBuilder.put(
        OutputGroupInfo.COMPILATION_PREREQUISITES,
        CcCommon.collectCompilationPrerequisites(ruleContext, ccCompilationContext));
    outputGroupsBuilder.putAll(
        CcCommon.createSaveFeatureStateArtifacts(
            cppConfiguration, featureConfiguration, ruleContext));
    return outputGroupsBuilder.build();
  }

  @Immutable
  private static class PublicHeaders {
    private final ImmutableList<Artifact> headers;
    private final ImmutableList<Artifact> moduleMapHeaders;
    private final @Nullable PathFragment virtualIncludePath;
    private final NestedSet<Pair<String, String>> virtualToOriginalHeaders;

    private PublicHeaders(
        ImmutableList<Artifact> headers,
        ImmutableList<Artifact> moduleMapHeaders,
        PathFragment virtualIncludePath,
        NestedSet<Pair<String, String>> virtualToOriginalHeaders) {
      this.headers = headers;
      this.moduleMapHeaders = moduleMapHeaders;
      this.virtualIncludePath = virtualIncludePath;
      this.virtualToOriginalHeaders = virtualToOriginalHeaders;
    }

    private ImmutableList<Artifact> getHeaders() {
      return headers;
    }

    private ImmutableList<Artifact> getModuleMapHeaders() {
      return moduleMapHeaders;
    }

    @Nullable
    private PathFragment getVirtualIncludePath() {
      return virtualIncludePath;
    }
  }

  private PublicHeaders computePublicHeaders() {
    PathFragment prefix = null;
    if (includePrefix != null) {
      prefix = PathFragment.create(includePrefix);
      if (PathFragment.containsUplevelReferences(includePrefix)) {
        ruleErrorConsumer.ruleError("include prefix should not contain uplevel references");
      }
      if (prefix.isAbsolute()) {
        ruleErrorConsumer.ruleError("include prefix should be a relative path");
      }
    }

    PathFragment stripPrefix;
    if (stripIncludePrefix != null) {
      if (PathFragment.containsUplevelReferences(stripIncludePrefix)) {
        ruleErrorConsumer.ruleError("strip include prefix should not contain uplevel references");
      }
      stripPrefix = PathFragment.create(stripIncludePrefix);
      if (stripPrefix.isAbsolute()) {
        stripPrefix =
            label
                .getPackageIdentifier()
                .getRepository()
                .getSourceRoot()
                .getRelative(stripPrefix.toRelative());
      } else {
        stripPrefix = actionConstructionContext.getPackageDirectory().getRelative(stripPrefix);
      }
    } else if (prefix != null) {
      stripPrefix = actionConstructionContext.getPackageDirectory();
    } else {
      stripPrefix = null;
    }

    if (stripPrefix == null && prefix == null) {
      // Simple case, no magic needed
      return new PublicHeaders(
          ImmutableList.copyOf(Iterables.concat(publicHeaders, nonModuleMapHeaders)),
          ImmutableList.copyOf(publicHeaders),
          /*virtualIncludePath=*/ null,
          /* virtualToOriginalHeaders= */ NestedSetBuilder.create(Order.STABLE_ORDER));
    }

    if (ruleErrorConsumer.hasErrors()) {
      return new PublicHeaders(
          /* headers= */ ImmutableList.of(),
          /* moduleMapHeaders */ ImmutableList.of(),
          /* virtualIncludePath */ null,
          /* virtualToOriginalHeaders */ NestedSetBuilder.create(Order.STABLE_ORDER));
    }

    ImmutableList.Builder<Artifact> moduleHeadersBuilder = ImmutableList.builder();
    NestedSetBuilder<Pair<String, String>> virtualToOriginalHeaders =
        NestedSetBuilder.stableOrder();
    for (Artifact originalHeader : publicHeaders) {
      if (!originalHeader.getRootRelativePath().startsWith(stripPrefix)) {
        ruleErrorConsumer.ruleError(
            String.format(
                "header '%s' is not under the specified strip prefix '%s'",
                originalHeader.getExecPathString(), stripPrefix.getPathString()));
        continue;
      }

      PathFragment includePath = originalHeader.getRootRelativePath().relativeTo(stripPrefix);
      if (prefix != null) {
        includePath = prefix.getRelative(includePath);
      }

      if (!originalHeader.getExecPath().equals(includePath)) {
        Artifact virtualHeader =
            actionConstructionContext.getUniqueDirectoryArtifact(
                "_virtual_includes",
                includePath,
                actionConstructionContext.getBinOrGenfilesDirectory());
        actionRegistry.registerAction(
            SymlinkAction.toArtifact(
                actionConstructionContext.getActionOwner(),
                originalHeader,
                virtualHeader,
                "Symlinking virtual headers for " + label));
        moduleHeadersBuilder.add(virtualHeader);
        if (configuration.isCodeCoverageEnabled()) {
          virtualToOriginalHeaders.add(
              Pair.of(virtualHeader.getExecPathString(), originalHeader.getExecPathString()));
        }
      } else {
        moduleHeadersBuilder.add(originalHeader);
      }
    }

    ImmutableList<Artifact> moduleMapHeaders = moduleHeadersBuilder.build();
    ImmutableList<Artifact> virtualHeaders =
        ImmutableList.<Artifact>builder()
            .addAll(moduleMapHeaders)
            .addAll(nonModuleMapHeaders)
            .build();

    return new PublicHeaders(
        virtualHeaders,
        moduleMapHeaders,
        actionConstructionContext
            .getBinOrGenfilesDirectory()
            .getExecPath()
            .getRelative(
                actionConstructionContext.getUniqueDirectory(
                    PathFragment.create("_virtual_includes"))),
        virtualToOriginalHeaders.build());
  }

  /**
   * Create {@code CcCompilationContext} for cc compile action from generated inputs.
   */
  private CcCompilationContext initializeCcCompilationContext() {
    CcCompilationContext.Builder ccCompilationContextBuilder =
        CcCompilationContext.builder(actionConstructionContext, configuration, label);

    // Setup the include path; local include directories come before those inherited from deps or
    // from the toolchain; in case of aliasing (same include file found on different entries),
    // prefer the local include rather than the inherited one.

    // Add in the roots for well-formed include names for source files and
    // generated files. It is important that the execRoot (EMPTY_FRAGMENT) comes
    // before the genfilesFragment to preferably pick up source files. Otherwise
    // we might pick up stale generated files.
    PathFragment repositoryPath =
        label.getPackageIdentifier().getRepository().getPathUnderExecRoot();
    ccCompilationContextBuilder.addQuoteIncludeDir(repositoryPath);
    ccCompilationContextBuilder.addQuoteIncludeDir(
        configuration.getGenfilesFragment().getRelative(repositoryPath));
    ccCompilationContextBuilder.addQuoteIncludeDir(
        configuration.getBinFragment().getRelative(repositoryPath));

    ccCompilationContextBuilder.addSystemIncludeDirs(systemIncludeDirs);

    for (PathFragment includeDir : includeDirs) {
      ccCompilationContextBuilder.addIncludeDir(includeDir);
    }

    PublicHeaders publicHeaders = computePublicHeaders();
    if (publicHeaders.getVirtualIncludePath() != null) {
      ccCompilationContextBuilder.addIncludeDir(publicHeaders.getVirtualIncludePath());
    }

    if (configuration.isCodeCoverageEnabled()) {
      // Populate the map only when code coverage collection is enabled, to report the actual source
      // file name in the coverage output file.
      ccCompilationContextBuilder.addVirtualToOriginalHeaders(
          publicHeaders.virtualToOriginalHeaders);
    }

    ccCompilationContextBuilder.mergeDependentCcCompilationContexts(ccCompilationContexts);
    mergeToolchainDependentCcCompilationContext(ccToolchain, ccCompilationContextBuilder);

    // But defines come after those inherited from deps.
    ccCompilationContextBuilder.addDefines(defines);

    // There are no ordering constraints for declared include dirs/srcs.
    ccCompilationContextBuilder.addDeclaredIncludeSrcs(publicHeaders.getHeaders());
    ccCompilationContextBuilder.addDeclaredIncludeSrcs(publicTextualHeaders);
    ccCompilationContextBuilder.addDeclaredIncludeSrcs(privateHeaders);
    ccCompilationContextBuilder.addDeclaredIncludeSrcs(additionalInputs);
    ccCompilationContextBuilder.addNonCodeInputs(additionalInputs);
    ccCompilationContextBuilder.addModularHdrs(publicHeaders.getHeaders());
    ccCompilationContextBuilder.addModularHdrs(privateHeaders);
    ccCompilationContextBuilder.addTextualHdrs(publicTextualHeaders);

    // Add this package's dir to declaredIncludeDirs, & this rule's headers to declaredIncludeSrcs
    // Note: no include dir for STRICT mode.
    if (headersCheckingMode == HeadersCheckingMode.LOOSE) {
      ccCompilationContextBuilder.addDeclaredIncludeDir(label.getPackageFragment());
      for (PathFragment looseIncludeDir : looseIncludeDirs) {
        ccCompilationContextBuilder.addDeclaredIncludeDir(looseIncludeDir);
      }
      ccCompilationContextBuilder.setHeadersCheckingMode(headersCheckingMode);
    }

    if (featureConfiguration.isEnabled(CppRuleClasses.MODULE_MAPS)) {
      if (cppModuleMap == null) {
        cppModuleMap =
            CppHelper.createDefaultCppModuleMap(
                actionConstructionContext, configuration, label, /*suffix=*/ "");
      }

      ccCompilationContextBuilder.setPropagateCppModuleMapAsActionInput(
          propagateModuleMapToCompileAction);
      ccCompilationContextBuilder.setCppModuleMap(cppModuleMap);
      // There are different modes for module compilation:
      // 1. We create the module map and compile the module so that libraries depending on us can
      //    use the resulting module artifacts in their compilation (compiled is true).
      // 2. We create the module map so that libraries depending on us will include the headers
      //    textually (compiled is false).
      boolean compiled =
          featureConfiguration.isEnabled(CppRuleClasses.HEADER_MODULES)
              || featureConfiguration.isEnabled(CppRuleClasses.COMPILE_ALL_MODULES);
      Iterable<CppModuleMap> dependentModuleMaps = collectModuleMaps();

      if (generateModuleMap) {
        Optional<Artifact> umbrellaHeader = cppModuleMap.getUmbrellaHeader();
        if (umbrellaHeader.isPresent()) {
          actionRegistry.registerAction(
              createUmbrellaHeaderAction(umbrellaHeader.get(), publicHeaders));
        }

        actionRegistry.registerAction(
            createModuleMapAction(cppModuleMap, publicHeaders, dependentModuleMaps, compiled));
      }
      if (getGeneratesPicHeaderModule()) {
        ccCompilationContextBuilder.setPicHeaderModule(
            getPicHeaderModule(cppModuleMap.getArtifact()));
      }
      if (getGeneratesNoPicHeaderModule()) {
        ccCompilationContextBuilder.setHeaderModule(getHeaderModule(cppModuleMap.getArtifact()));
      }
      if (!compiled
          && featureConfiguration.isEnabled(CppRuleClasses.PARSE_HEADERS)
          && featureConfiguration.isEnabled(CppRuleClasses.USE_HEADER_MODULES)
          && cppConfiguration.getParseHeadersVerifiesModules()) {
        // Here, we are creating a compiled module to verify that headers are self-contained and
        // modules ready, but we don't use the corresponding module map or compiled file anywhere
        // else.
        CppModuleMap verificationMap =
            CppHelper.createDefaultCppModuleMap(
                actionConstructionContext, configuration, label, /*suffix=*/ ".verify");
        actionRegistry.registerAction(
            createModuleMapAction(
                verificationMap, publicHeaders, dependentModuleMaps, /*compiledModule=*/ true));
        ccCompilationContextBuilder.setVerificationModuleMap(verificationMap);
      }
    }
    ccCompilationContextBuilder.setPurpose(purpose);
    ccCompilationContextBuilder.addQuoteIncludeDirs(quoteIncludeDirs);
    return ccCompilationContextBuilder.build();
  }

  /**
   * Collects all preprocessed header files (*.h.processed) from dependencies and the current rule.
   */
  public static NestedSet<Artifact> collectHeaderTokens(
      RuleContext ruleContext,
      CppConfiguration cppConfiguration,
      CcCompilationOutputs ccCompilationOutputs) {
    NestedSetBuilder<Artifact> headerTokens = NestedSetBuilder.stableOrder();
    for (OutputGroupInfo dep :
        ruleContext.getPrerequisites("deps", Mode.TARGET, OutputGroupInfo.SKYLARK_CONSTRUCTOR)) {
      headerTokens.addTransitive(dep.getOutputGroup(CcCompilationHelper.HIDDEN_HEADER_TOKENS));
    }
    if (cppConfiguration.processHeadersInDependencies()) {
      headerTokens.addAll(ccCompilationOutputs.getHeaderTokenFiles());
    }
    return headerTokens.build();
  }

  public void registerAdditionalModuleMap(CppModuleMap cppModuleMap) {
    this.additionalCppModuleMaps.add(Preconditions.checkNotNull(cppModuleMap));
  }

  /** Don't generate a module map for this target if a custom module map is provided. */
  public CcCompilationHelper doNotGenerateModuleMap() {
    generateModuleMap = false;
    return this;
  }

  /**
   * Sets the purpose for the {@code CcCompilationContext}.
   *
   * @see CcCompilationContext.Builder#setPurpose
   * @param purpose must be a string which is suitable for use as a filename. A single rule may have
   *     many middlemen with distinct purposes.
   */
  public CcCompilationHelper setPurpose(@Nullable String purpose) {
    this.purpose = purpose;
    return this;
  }

  private UmbrellaHeaderAction createUmbrellaHeaderAction(
      Artifact umbrellaHeader, PublicHeaders publicHeaders) {
    return new UmbrellaHeaderAction(
        actionConstructionContext.getActionOwner(),
        umbrellaHeader,
        featureConfiguration.isEnabled(CppRuleClasses.ONLY_DOTH_HEADERS_IN_MODULE_MAPS)
            ? Iterables.filter(publicHeaders.getModuleMapHeaders(), CppFileTypes.MODULE_MAP_HEADER)
            : publicHeaders.getModuleMapHeaders(),
        additionalExportedHeaders);
  }

  private CppModuleMapAction createModuleMapAction(
      CppModuleMap moduleMap,
      PublicHeaders publicHeaders,
      Iterable<CppModuleMap> dependentModuleMaps,
      boolean compiledModule) {
    return new CppModuleMapAction(
        actionConstructionContext.getActionOwner(),
        moduleMap,
        featureConfiguration.isEnabled(CppRuleClasses.EXCLUDE_PRIVATE_HEADERS_IN_MODULE_MAPS)
            ? ImmutableList.of()
            : privateHeaders,
        featureConfiguration.isEnabled(CppRuleClasses.ONLY_DOTH_HEADERS_IN_MODULE_MAPS)
            ? Iterables.filter(publicHeaders.getModuleMapHeaders(), CppFileTypes.MODULE_MAP_HEADER)
            : publicHeaders.getModuleMapHeaders(),
        dependentModuleMaps,
        additionalExportedHeaders,
        compiledModule,
        featureConfiguration.isEnabled(CppRuleClasses.MODULE_MAP_HOME_CWD),
        featureConfiguration.isEnabled(CppRuleClasses.GENERATE_SUBMODULES),
        !featureConfiguration.isEnabled(CppRuleClasses.MODULE_MAP_WITHOUT_EXTERN_MODULE));
  }

  public static CcCompilationContext getStlCcCompilationContext(RuleContext ruleContext) {
    if (ruleContext.attributes().has("$stl", BuildType.LABEL)) {
      CcInfo ccInfo = ruleContext.getPrerequisite("$stl", Mode.TARGET, CcInfo.PROVIDER);
      if (ccInfo != null) {
        return ccInfo.getCcCompilationContext();
      } else {
        return CcCompilationContext.EMPTY;
      }
    } else {
      return CcCompilationContext.EMPTY;
    }
  }

  private Iterable<CppModuleMap> collectModuleMaps() {
    // Cpp module maps may be null for some rules. We filter the nulls out at the end.
    List<CppModuleMap> result =
        ccCompilationContexts.stream()
            .map(CPP_DEPS_TO_MODULES)
            .collect(toCollection(ArrayList::new));

    if (ccToolchain != null) {
      result.add(ccToolchain.getCcInfo().getCcCompilationContext().getCppModuleMap());
    }
    for (CppModuleMap additionalCppModuleMap : additionalCppModuleMaps) {
      result.add(additionalCppModuleMap);
    }

    return Iterables.filter(result, Predicates.notNull());
  }

  /** @return whether this target needs to generate a pic header module. */
  private boolean getGeneratesPicHeaderModule() {
    return shouldProvideHeaderModules() && !fake && generatePicAction;
  }

  /** @return whether this target needs to generate a no-PIC header module. */
  private boolean getGeneratesNoPicHeaderModule() {
    return shouldProvideHeaderModules() && !fake && generateNoPicAction;
  }

  /** @return whether we want to provide header modules for the current target. */
  private boolean shouldProvideHeaderModules() {
    return featureConfiguration.isEnabled(CppRuleClasses.HEADER_MODULES);
  }

  /** @return the no-PIC header module artifact for the current target. */
  private Artifact getHeaderModule(Artifact moduleMapArtifact) {
    PathFragment objectDir = CppHelper.getObjDirectory(label);
    PathFragment outputName =
        objectDir.getRelative(moduleMapArtifact.getRootRelativePath().getBaseName());
    return actionConstructionContext.getRelatedArtifact(outputName, ".pcm");
  }

  /** @return the pic header module artifact for the current target. */
  private Artifact getPicHeaderModule(Artifact moduleMapArtifact) {
    PathFragment objectDir = CppHelper.getObjDirectory(label);
    PathFragment outputName =
        objectDir.getRelative(moduleMapArtifact.getRootRelativePath().getBaseName());
    return actionConstructionContext.getRelatedArtifact(outputName, ".pic.pcm");
  }

  /**
   * Calculate the output names for object file paths from a set of source files.
   *
   * <p>The object file path is constructed in the following format:
   *    <bazel-bin>/<target_package_path>/_objs/<target_name>/<output_name>.<obj_extension>
   * When there's no two source files having the same basename:
   *   <output_name> = <prefixDir>/<source_file_base_name>
   * otherwise:
   *   <output_name> = <prefixDir>/N/<source_file_base_name>,
   *   N = the file’s order among the source files with the same basename, starts with 0
   *
   * <p>Examples: 1. Output names for ["lib1/foo.cc", "lib2/bar.cc"] are ["foo", "bar"]
   *              2. Output names for ["foo.cc", "bar.cc", "foo.cpp", "lib/foo.cc"]
   *                 are ["0/foo", "bar", "1/foo", "2/foo"]
   */
  private ImmutableMap<Artifact, String> calculateOutputNameMap(
      NestedSet<Artifact> sourceArtifacts, String prefixDir) {
    ImmutableMap.Builder<Artifact, String> builder = ImmutableMap.builder();

    HashMap<String, Integer> count = new LinkedHashMap<>();
    HashMap<String, Integer> number = new LinkedHashMap<>();
    for (Artifact source : sourceArtifacts) {
      String outputName =
          FileSystemUtils.removeExtension(source.getRootRelativePath()).getBaseName();
      count.put(outputName.toLowerCase(),
          count.getOrDefault(outputName.toLowerCase(), 0) + 1);
    }

    for (Artifact source : sourceArtifacts) {
      String outputName =
          FileSystemUtils.removeExtension(source.getRootRelativePath()).getBaseName();
      if (count.getOrDefault(outputName.toLowerCase(), 0) > 1) {
        int num = number.getOrDefault(outputName.toLowerCase(), 0);
        number.put(outputName.toLowerCase(), num + 1);
        outputName = num + "/" + outputName;
      }
      // If prefixDir is set, prepend it to the outputName
      if (prefixDir != null) {
        outputName = prefixDir + "/" + outputName;
      }
      builder.put(source, outputName);
    }

    return builder.build();
  }

  /**
   * Calculate outputNameMap for different source types separately. Returns a merged outputNameMap
   * for all artifacts.
   */
  private ImmutableMap<Artifact, String> calculateOutputNameMapByType(
      Map<Artifact, CppSource> sources, String prefixDir) {
    ImmutableMap.Builder<Artifact, String> builder = ImmutableMap.builder();
    builder.putAll(
        calculateOutputNameMap(
            getSourceArtifactsByType(sources, CppSource.Type.SOURCE), prefixDir));
    builder.putAll(
        calculateOutputNameMap(
            getSourceArtifactsByType(sources, CppSource.Type.HEADER), prefixDir));
    // TODO(plf): Removing CLIF logic
    builder.putAll(
        calculateOutputNameMap(
            getSourceArtifactsByType(sources, CppSource.Type.CLIF_INPUT_PROTO), prefixDir));
    return builder.build();
  }

  private NestedSet<Artifact> getSourceArtifactsByType(
      Map<Artifact, CppSource> sources, CppSource.Type type) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.stableOrder();
    result.addAll(
        sources
            .values()
            .stream()
            .filter(source -> source.getType().equals(type))
            .map(CppSource::getSource)
            .collect(Collectors.toList()));
    return result.build();
  }

  /**
   * Constructs the C++ compiler actions. It generally creates one action for every specified source
   * file. It takes into account fake-ness, coverage, and PIC, in addition to using the settings
   * specified on the current object. This method should only be called once.
   */
  private CcCompilationOutputs createCcCompileActions() throws RuleErrorException {
    CcCompilationOutputs.Builder result = CcCompilationOutputs.builder();
    Preconditions.checkNotNull(ccCompilationContext);

    if (shouldProvideHeaderModules()) {
      Label moduleMapLabel =
          Label.parseAbsoluteUnchecked(ccCompilationContext.getCppModuleMap().getName());
      Collection<Artifact> modules =
          createModuleAction(result, ccCompilationContext.getCppModuleMap());
      if (featureConfiguration.isEnabled(CppRuleClasses.HEADER_MODULE_CODEGEN)) {
        for (Artifact module : modules) {
          // TODO(djasper): Investigate whether we need to use a label separate from that of the
          // module map. It is used for per-file-copts.
          createModuleCodegenAction(result, moduleMapLabel, module);
        }
      }
    } else if (ccCompilationContext.getVerificationModuleMap() != null) {
      Collection<Artifact> modules =
          createModuleAction(result, ccCompilationContext.getVerificationModuleMap());
      for (Artifact module : modules) {
        result.addHeaderTokenFile(module);
      }
    }

    ImmutableMap<Artifact, String> outputNameMap = null;

    String outputNamePrefixDir = null;
    // purpose is only used by objc rules, it ends with either "_non_objc_arc" or "_objc_arc".
    // Here we use it to distinguish arc and non-arc compilation.
    if (purpose != null) {
      outputNamePrefixDir = purpose.endsWith("_non_objc_arc") ? "non_arc" : "arc";
    }
    outputNameMap = calculateOutputNameMapByType(compilationUnitSources, outputNamePrefixDir);

    for (CppSource source : compilationUnitSources.values()) {
      Artifact sourceArtifact = source.getSource();
      Label sourceLabel = source.getLabel();
      CppCompileActionBuilder builder = initializeCompileAction(sourceArtifact);

      builder
          .setSemantics(semantics)
          .addMandatoryInputs(additionalCompilationInputs)
          .addAdditionalIncludeScanningRoots(additionalIncludeScanningRoots);

      boolean bitcodeOutput =
          featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)
              && CppFileTypes.LTO_SOURCE.matches(sourceArtifact.getFilename());

      String outputName = outputNameMap.get(sourceArtifact);

      if (!sourceArtifact.isTreeArtifact()) {
        switch (source.getType()) {
          case HEADER:
            createHeaderAction(
                sourceLabel, outputName, result, builder, isGenerateDotdFile(sourceArtifact));
            break;
          default:
            createSourceAction(
                sourceLabel,
                outputName,
                result,
                sourceArtifact,
                builder,
                // TODO(plf): Continue removing CLIF logic from C++. Follow up changes would include
                // refactoring CppSource.Type and ArtifactCategory to be classes instead of enums
                // that could be instantiated with arbitrary values.
                source.getType() == CppSource.Type.CLIF_INPUT_PROTO
                    ? ArtifactCategory.CLIF_OUTPUT_PROTO
                    : ArtifactCategory.OBJECT_FILE,
                ccCompilationContext.getCppModuleMap(),
                /* addObject= */ true,
                isCodeCoverageEnabled,
                // The source action does not generate dwo when it has bitcode
                // output (since it isn't generating a native object with debug
                // info). In that case the LtoBackendAction will generate the dwo.
                ccToolchain.shouldCreatePerObjectDebugInfo(featureConfiguration, cppConfiguration),
                bitcodeOutput,
                isGenerateDotdFile(sourceArtifact));
            break;
        }
      } else {
        switch (source.getType()) {
          case HEADER:
            Artifact headerTokenFile =
                createCompileActionTemplate(
                    source,
                    outputName,
                    builder,
                    ImmutableList.of(
                        ArtifactCategory.GENERATED_HEADER, ArtifactCategory.PROCESSED_HEADER),
                    false);
            result.addHeaderTokenFile(headerTokenFile);
            break;
          case SOURCE:
            Artifact objectFile =
                createCompileActionTemplate(
                    source,
                    outputName,
                    builder,
                    ImmutableList.of(ArtifactCategory.OBJECT_FILE),
                    false);
            result.addObjectFile(objectFile);

            if (generatePicAction) {
              Artifact picObjectFile =
                  createCompileActionTemplate(
                      source,
                      outputName,
                      builder,
                      ImmutableList.of(ArtifactCategory.PIC_OBJECT_FILE),
                      true);
              result.addPicObjectFile(picObjectFile);
            }
            break;
          default:
            throw new IllegalStateException(
                "Encountered invalid source types when creating CppCompileActionTemplates");
        }
      }
    }

    return result.build();
  }

  private Artifact createCompileActionTemplate(
      CppSource source,
      String outputName,
      CppCompileActionBuilder builder,
      Iterable<ArtifactCategory> outputCategories,
      boolean usePic) {
    SpecialArtifact sourceArtifact = (SpecialArtifact) source.getSource();
    SpecialArtifact outputFiles =
        CppHelper.getCompileOutputTreeArtifact(
            actionConstructionContext, label, sourceArtifact, outputName, usePic);
    // Dotd file output is specified in the execution phase.
    builder.setOutputs(outputFiles, /* dotdFile= */ null);
    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            /* sourceLabel= */ null,
            usePic,
            /* ccRelativeName= */ null,
            ccCompilationContext.getCppModuleMap(),
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            /* additionalBuildVariables= */ ImmutableMap.of()));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    // Make sure this builder doesn't reference ruleContext outside of analysis phase.
    CppCompileActionTemplate actionTemplate =
        new CppCompileActionTemplate(
            sourceArtifact,
            outputFiles,
            CppHelper.getDotdOutputTreeArtifact(
                actionConstructionContext, label, sourceArtifact, outputName, usePic),
            builder,
            ccToolchain,
            outputCategories,
            actionConstructionContext.getActionOwner());
    actionRegistry.registerAction(actionTemplate);

    return outputFiles;
  }

  /**
   * Return flags that were specified on the Blaze command line. Take the filetype of sourceFilename
   * into account.
   */
  public static ImmutableList<String> getCoptsFromOptions(
      CppConfiguration config, String sourceFilename) {
    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();

    flagsBuilder.addAll(config.getCopts());

    if (CppFileTypes.C_SOURCE.matches(sourceFilename)) {
      flagsBuilder.addAll(config.getConlyopts());
    }

    if (CppFileTypes.CPP_SOURCE.matches(sourceFilename)
        || CppFileTypes.CPP_HEADER.matches(sourceFilename)
        || CppFileTypes.CPP_MODULE_MAP.matches(sourceFilename)
        || CppFileTypes.CLIF_INPUT_PROTO.matches(sourceFilename)) {
      flagsBuilder.addAll(config.getCxxopts());
    }

    return flagsBuilder.build();
  }

  private ImmutableList<String> getCopts(Artifact sourceFile, Label sourceLabel) {
    ImmutableList.Builder<String> coptsList = ImmutableList.builder();
    coptsList.addAll(getCoptsFromOptions(cppConfiguration, sourceFile.getExecPathString()));
    coptsList.addAll(copts);
    if (sourceFile != null && sourceLabel != null) {
      coptsList.addAll(collectPerFileCopts(sourceFile, sourceLabel));
    }
    return coptsList.build();
  }

  private CcToolchainVariables setupCompileBuildVariables(
      CppCompileActionBuilder builder,
      Label sourceLabel,
      boolean usePic,
      PathFragment ccRelativeName,
      CppModuleMap cppModuleMap,
      Artifact gcnoFile,
      boolean isUsingFission,
      Artifact dwoFile,
      Artifact ltoIndexingFile,
      ImmutableMap<String, String> additionalBuildVariables) {
    Artifact sourceFile = builder.getSourceFile();
    String dotdFileExecPath = null;
    if (builder.getDotdFile() != null) {
      dotdFileExecPath = builder.getDotdFile().getExecPathString();
    }
    ImmutableMap.Builder<String, String> allAdditionalBuildVariables = ImmutableMap.builder();
    allAdditionalBuildVariables.putAll(additionalBuildVariables);
    if (ccRelativeName != null) {
      configureFdoBuildVariables(
          allAdditionalBuildVariables,
          builder,
          featureConfiguration,
          fdoContext,
          cppConfiguration.getFdoInstrument(),
          cppConfiguration);
    }
    return CompileBuildVariables.setupVariablesOrReportRuleError(
        ruleErrorConsumer,
        featureConfiguration,
        ccToolchain,
        configuration.getOptions(),
        cppConfiguration,
        toPathString(sourceFile),
        toPathString(builder.getOutputFile()),
        toPathString(gcnoFile),
        isUsingFission,
        toPathString(dwoFile),
        toPathString(ltoIndexingFile),
        ImmutableList.of(),
        getCopts(builder.getSourceFile(), sourceLabel),
        cppModuleMap,
        usePic,
        builder.getTempOutputFile(),
        CppHelper.getFdoBuildStamp(cppConfiguration, fdoContext, featureConfiguration),
        dotdFileExecPath,
        ImmutableList.copyOf(variablesExtensions),
        allAdditionalBuildVariables.build(),
        ccCompilationContext.getDirectModuleMaps(),
        ccCompilationContext.getIncludeDirs(),
        ccCompilationContext.getQuoteIncludeDirs(),
        ccCompilationContext.getSystemIncludeDirs(),
        ccCompilationContext.getDefines());
  }

  private static String toPathString(Artifact a) {
    return a == null ? null : a.getExecPathString();
  }

  /**
   * Returns a {@code CppCompileActionBuilder} with the common fields for a C++ compile action being
   * initialized.
   */
  private CppCompileActionBuilder initializeCompileAction(Artifact sourceArtifact) {
    CppCompileActionBuilder builder =
        new CppCompileActionBuilder(
            actionConstructionContext, grepIncludes, ccToolchain, configuration);
    builder.setSourceFile(sourceArtifact);
    builder.setCcCompilationContext(ccCompilationContext);
    builder.setCoptsFilter(coptsFilter);
    builder.setFeatureConfiguration(featureConfiguration);
    return builder;
  }

  private void createModuleCodegenAction(
      CcCompilationOutputs.Builder result, Label sourceLabel, Artifact module)
      throws RuleErrorException {
    if (fake) {
      // We can't currently foresee a situation where we'd want nocompile tests for module codegen.
      // If we find one, support needs to be added here.
      return;
    }
    String outputName = module.getRootRelativePath().getBaseName();

    // TODO(djasper): Make this less hacky after refactoring how the PIC/noPIC actions are created.
    boolean pic = module.getFilename().contains(".pic.");

    CppCompileActionBuilder builder = initializeCompileAction(module);
    builder.setSemantics(semantics);
    builder.setPicMode(pic);
    builder.setOutputs(
        actionConstructionContext,
        ruleErrorConsumer,
        label,
        ArtifactCategory.OBJECT_FILE,
        outputName,
        isGenerateDotdFile(module));
    PathFragment ccRelativeName = module.getRootRelativePath();

    String gcnoFileName =
        CppHelper.getArtifactNameForCategory(
            ruleErrorConsumer, ccToolchain, ArtifactCategory.COVERAGE_DATA_FILE, outputName);
    // TODO(djasper): This is now duplicated. Refactor the various create..Action functions.
    Artifact gcnoFile =
        isCodeCoverageEnabled
            ? CppHelper.getCompileOutputArtifact(
                actionConstructionContext, label, gcnoFileName, configuration)
            : null;

    boolean generateDwo =
        ccToolchain.shouldCreatePerObjectDebugInfo(featureConfiguration, cppConfiguration);
    Artifact dwoFile = generateDwo ? getDwoFile(builder.getOutputFile()) : null;
    // TODO(tejohnson): Add support for ThinLTO if needed.
    boolean bitcodeOutput =
        featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)
            && CppFileTypes.LTO_SOURCE.matches(module.getFilename());
    Preconditions.checkState(!bitcodeOutput);

    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            sourceLabel,
            /* usePic= */ pic,
            ccRelativeName,
            ccCompilationContext.getCppModuleMap(),
            gcnoFile,
            generateDwo,
            dwoFile,
            /* ltoIndexingFile= */ null,
            /* additionalBuildVariables= */ ImmutableMap.of()));

    builder.setGcnoFile(gcnoFile);
    builder.setDwoFile(dwoFile);

    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    CppCompileAction compileAction = builder.buildOrThrowRuleError(ruleErrorConsumer);
    actionRegistry.registerAction(compileAction);
    Artifact objectFile = compileAction.getOutputFile();
    if (pic) {
      result.addPicObjectFile(objectFile);
    } else {
      result.addObjectFile(objectFile);
    }
  }

  /** Returns true if Dotd file should be generated. */
  private boolean isGenerateDotdFile(Artifact sourceArtifact) {
    return CppFileTypes.headerDiscoveryRequired(sourceArtifact)
        && !featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES);
  }

  private void createHeaderAction(
      Label sourceLabel,
      String outputName,
      CcCompilationOutputs.Builder result,
      CppCompileActionBuilder builder,
      boolean generateDotd)
      throws RuleErrorException {
    String outputNameBase =
        CppHelper.getArtifactNameForCategory(
            ruleErrorConsumer, ccToolchain, ArtifactCategory.GENERATED_HEADER, outputName);

    builder
        .setOutputs(
            actionConstructionContext,
            ruleErrorConsumer,
            label,
            ArtifactCategory.PROCESSED_HEADER,
            outputNameBase,
            generateDotd)
        // If we generate pic actions, we prefer the header actions to use the pic artifacts.
        .setPicMode(generatePicAction);
    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            sourceLabel,
            generatePicAction,
            /* ccRelativeName= */ null,
            ccCompilationContext.getCppModuleMap(),
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            /* additionalBuildVariables= */ ImmutableMap.of()));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    CppCompileAction compileAction = builder.buildOrThrowRuleError(ruleErrorConsumer);
    actionRegistry.registerAction(compileAction);
    Artifact tokenFile = compileAction.getOutputFile();
    result.addHeaderTokenFile(tokenFile);
  }

  private Collection<Artifact> createModuleAction(
      CcCompilationOutputs.Builder result, CppModuleMap cppModuleMap) throws RuleErrorException {
    Artifact moduleMapArtifact = cppModuleMap.getArtifact();
    CppCompileActionBuilder builder = initializeCompileAction(moduleMapArtifact);

    builder.setSemantics(semantics);

    // A header module compile action is just like a normal compile action, but:
    // - the compiled source file is the module map
    // - it creates a header module (.pcm file).
    return createSourceAction(
        Label.parseAbsoluteUnchecked(cppModuleMap.getName()),
        FileSystemUtils.removeExtension(moduleMapArtifact.getRootRelativePath()).getBaseName(),
        result,
        moduleMapArtifact,
        builder,
        ArtifactCategory.CPP_MODULE,
        cppModuleMap,
        /* addObject= */ false,
        /* enableCoverage= */ false,
        /* generateDwo= */ false,
        /* bitcodeOutput= */ false,
        isGenerateDotdFile(moduleMapArtifact));
  }

  private Collection<Artifact> createSourceAction(
      Label sourceLabel,
      String outputName,
      CcCompilationOutputs.Builder result,
      Artifact sourceArtifact,
      CppCompileActionBuilder builder,
      ArtifactCategory outputCategory,
      CppModuleMap cppModuleMap,
      boolean addObject,
      boolean enableCoverage,
      boolean generateDwo,
      boolean bitcodeOutput,
      boolean generateDotd)
      throws RuleErrorException {
    ImmutableList.Builder<Artifact> directOutputs = new ImmutableList.Builder<>();
    PathFragment ccRelativeName = sourceArtifact.getRootRelativePath();
    if (fake) {
      boolean usePic = !generateNoPicAction;
      createFakeSourceAction(
          sourceLabel,
          outputName,
          result,
          builder,
          outputCategory,
          addObject,
          ccRelativeName,
          usePic,
          generateDotd);
    } else {
      // Create PIC compile actions (same as no-PIC, but use -fPIC and
      // generate .pic.o, .pic.d, .pic.gcno instead of .o, .d, .gcno.)
      if (generatePicAction) {
        String picOutputBase =
            CppHelper.getArtifactNameForCategory(
                ruleErrorConsumer, ccToolchain, ArtifactCategory.PIC_FILE, outputName);
        CppCompileActionBuilder picBuilder =
            copyAsPicBuilder(builder, picOutputBase, outputCategory, generateDotd);
        String gcnoFileName =
            CppHelper.getArtifactNameForCategory(
                ruleErrorConsumer, ccToolchain, ArtifactCategory.COVERAGE_DATA_FILE, picOutputBase);
        Artifact gcnoFile =
            enableCoverage
                ? CppHelper.getCompileOutputArtifact(
                    actionConstructionContext, label, gcnoFileName, configuration)
                : null;
        Artifact dwoFile =
            generateDwo && !bitcodeOutput ? getDwoFile(picBuilder.getOutputFile()) : null;
        Artifact ltoIndexingFile =
            bitcodeOutput ? getLtoIndexingFile(picBuilder.getOutputFile()) : null;

        picBuilder.setVariables(
            setupCompileBuildVariables(
                picBuilder,
                sourceLabel,
                /* usePic= */ true,
                ccRelativeName,
                ccCompilationContext.getCppModuleMap(),
                gcnoFile,
                generateDwo,
                dwoFile,
                ltoIndexingFile,
                /* additionalBuildVariables= */ ImmutableMap.of()));

        result.addTemps(
            createTempsActions(
                sourceArtifact,
                sourceLabel,
                outputName,
                picBuilder,
                /* usePic= */ true,
                /* generateDotd= */ generateDotd,
                ccRelativeName));

        picBuilder.setGcnoFile(gcnoFile);
        picBuilder.setDwoFile(dwoFile);
        picBuilder.setLtoIndexingFile(ltoIndexingFile);

        semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, picBuilder);
        CppCompileAction picAction = picBuilder.buildOrThrowRuleError(ruleErrorConsumer);
        actionRegistry.registerAction(picAction);
        directOutputs.add(picAction.getOutputFile());
        if (addObject) {
          result.addPicObjectFile(picAction.getOutputFile());

          if (bitcodeOutput) {
            result.addLtoBitcodeFile(
                picAction.getOutputFile(), ltoIndexingFile, getCopts(sourceArtifact, sourceLabel));
          }
        }
        if (dwoFile != null) {
          // Host targets don't produce .dwo files.
          result.addPicDwoFile(dwoFile);
        }
      }

      if (generateNoPicAction) {
        Artifact noPicOutputFile =
            CppHelper.getCompileOutputArtifact(
                actionConstructionContext,
                label,
                CppHelper.getArtifactNameForCategory(
                    ruleErrorConsumer, ccToolchain, outputCategory, outputName),
                configuration);
        builder.setOutputs(
            actionConstructionContext,
            ruleErrorConsumer,
            label,
            outputCategory,
            outputName,
            generateDotd);
        String gcnoFileName =
            CppHelper.getArtifactNameForCategory(
                ruleErrorConsumer, ccToolchain, ArtifactCategory.COVERAGE_DATA_FILE, outputName);

        // Create no-PIC compile actions
        Artifact gcnoFile =
            enableCoverage
                ? CppHelper.getCompileOutputArtifact(
                    actionConstructionContext, label, gcnoFileName, configuration)
                : null;

        Artifact noPicDwoFile = generateDwo && !bitcodeOutput ? getDwoFile(noPicOutputFile) : null;
        Artifact ltoIndexingFile =
            bitcodeOutput ? getLtoIndexingFile(builder.getOutputFile()) : null;

        builder.setVariables(
            setupCompileBuildVariables(
                builder,
                sourceLabel,
                /* usePic= */ false,
                ccRelativeName,
                cppModuleMap,
                gcnoFile,
                generateDwo,
                noPicDwoFile,
                ltoIndexingFile,
                /* additionalBuildVariables= */ ImmutableMap.of()));

        result.addTemps(
            createTempsActions(
                sourceArtifact,
                sourceLabel,
                outputName,
                builder,
                /* usePic= */ false,
                generateDotd,
                ccRelativeName));

        builder.setGcnoFile(gcnoFile);
        builder.setDwoFile(noPicDwoFile);
        builder.setLtoIndexingFile(ltoIndexingFile);

        semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
        CppCompileAction compileAction = builder.buildOrThrowRuleError(ruleErrorConsumer);
        actionRegistry.registerAction(compileAction);
        Artifact objectFile = compileAction.getOutputFile();
        directOutputs.add(objectFile);
        if (addObject) {
          result.addObjectFile(objectFile);
          if (bitcodeOutput) {
            result.addLtoBitcodeFile(
                objectFile, ltoIndexingFile, getCopts(sourceArtifact, sourceLabel));
          }
        }
        if (noPicDwoFile != null) {
          // Host targets don't produce .dwo files.
          result.addDwoFile(noPicDwoFile);
        }
      }
    }
    return directOutputs.build();
  }

  /**
   * Creates cpp PIC compile action builder from the given builder by adding necessary copt and
   * changing output and dotd file names.
   */
  private CppCompileActionBuilder copyAsPicBuilder(
      CppCompileActionBuilder builder,
      String outputName,
      ArtifactCategory outputCategory,
      boolean generateDotd)
      throws RuleErrorException {
    CppCompileActionBuilder picBuilder = new CppCompileActionBuilder(builder);
    picBuilder
        .setPicMode(true)
        .setOutputs(
            actionConstructionContext,
            ruleErrorConsumer,
            label,
            outputCategory,
            outputName,
            generateDotd);

    return picBuilder;
  }

  String getOutputNameBaseWith(String base, boolean usePic) throws RuleErrorException {
    return usePic
        ? CppHelper.getArtifactNameForCategory(
            ruleErrorConsumer, ccToolchain, ArtifactCategory.PIC_FILE, base)
        : base;
  }

  private void createFakeSourceAction(
      Label sourceLabel,
      String outputName,
      CcCompilationOutputs.Builder result,
      CppCompileActionBuilder builder,
      ArtifactCategory outputCategory,
      boolean addObject,
      PathFragment ccRelativeName,
      boolean usePic,
      boolean generateDotd)
      throws RuleErrorException {
    String outputNameBase = getOutputNameBaseWith(outputName, usePic);
    String tempOutputName =
        configuration
            .getBinFragment()
            .getRelative(CppHelper.getObjDirectory(label))
            .getRelative(
                CppHelper.getArtifactNameForCategory(
                    ruleErrorConsumer,
                    ccToolchain,
                    outputCategory,
                    getOutputNameBaseWith(outputName + ".temp", usePic)))
            .getPathString();
    builder
        .setPicMode(usePic)
        .setOutputs(
            actionConstructionContext,
            ruleErrorConsumer,
            label,
            outputCategory,
            outputNameBase,
            generateDotd)
        .setTempOutputFile(PathFragment.create(tempOutputName));

    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            sourceLabel,
            usePic,
            ccRelativeName,
            ccCompilationContext.getCppModuleMap(),
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            /* additionalBuildVariables= */ ImmutableMap.of()));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    CppCompileAction action = builder.buildOrThrowRuleError(ruleErrorConsumer);
    actionRegistry.registerAction(action);
    if (addObject) {
      if (usePic) {
        result.addPicObjectFile(action.getOutputFile());
      } else {
        result.addObjectFile(action.getOutputFile());
      }
    }
  }

  /** Returns true iff code coverage is enabled for the given target. */
  public static boolean isCodeCoverageEnabled(RuleContext ruleContext) {
    BuildConfiguration configuration = ruleContext.getConfiguration();
    if (configuration.isCodeCoverageEnabled()) {
      // If rule is matched by the instrumentation filter, enable instrumentation
      if (InstrumentedFilesCollector.shouldIncludeLocalSources(
          configuration, ruleContext.getLabel(), ruleContext.isTestTarget())) {
        return true;
      }
      // At this point the rule itself is not matched by the instrumentation filter. However, we
      // might still want to instrument C++ rules if one of the targets listed in "deps" is
      // instrumented and, therefore, can supply header files that we would want to collect code
      // coverage for. For example, think about cc_test rule that tests functionality defined in a
      // header file that is supplied by the cc_library.
      //
      // Note that we only check direct prerequisites and not the transitive closure. This is done
      // for two reasons:
      // a) It is a good practice to declare libraries which you directly rely on. Including headers
      //    from a library hidden deep inside the transitive closure makes build dependencies less
      //    readable and can lead to unexpected breakage.
      // b) Traversing the transitive closure for each C++ compile action would require more complex
      //    implementation (with caching results of this method) to avoid O(N^2) slowdown.
      if (ruleContext.getRule().isAttrDefined("deps", BuildType.LABEL_LIST)) {
        for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("deps", Mode.TARGET)) {
          CcInfo ccInfo = dep.get(CcInfo.PROVIDER);
          if (ccInfo != null
              && InstrumentedFilesCollector.shouldIncludeLocalSources(configuration, dep)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private ImmutableList<String> collectPerFileCopts(Artifact sourceFile, Label sourceLabel) {
    return cppConfiguration
        .getPerFileCopts()
        .stream()
        .filter(
            perLabelOptions ->
                (sourceLabel != null && perLabelOptions.isIncluded(sourceLabel))
                    || perLabelOptions.isIncluded(sourceFile))
        .map(PerLabelOptions::getOptions)
        .flatMap(options -> options.stream())
        .collect(ImmutableList.toImmutableList());
  }

  private Artifact getDwoFile(Artifact outputFile) {
    return actionConstructionContext.getRelatedArtifact(outputFile.getRootRelativePath(), ".dwo");
  }

  private Artifact getLtoIndexingFile(Artifact outputFile) {
    String ext = Iterables.getOnlyElement(CppFileTypes.LTO_INDEXING_OBJECT_FILE.getExtensions());
    return actionConstructionContext.getRelatedArtifact(outputFile.getRootRelativePath(), ext);
  }

  /** Create the actions for "--save_temps". */
  private ImmutableList<Artifact> createTempsActions(
      Artifact source,
      Label sourceLabel,
      String outputName,
      CppCompileActionBuilder builder,
      boolean usePic,
      boolean generateDotd,
      PathFragment ccRelativeName)
      throws RuleErrorException {
    if (!cppConfiguration.getSaveTemps()) {
      return ImmutableList.of();
    }

    String path = source.getFilename();
    boolean isCFile = CppFileTypes.C_SOURCE.matches(path);
    boolean isCppFile = CppFileTypes.CPP_SOURCE.matches(path);

    if (!isCFile && !isCppFile) {
      return ImmutableList.of();
    }

    ArtifactCategory category =
        isCFile ? ArtifactCategory.PREPROCESSED_C_SOURCE : ArtifactCategory.PREPROCESSED_CPP_SOURCE;

    String outputArtifactNameBase = getOutputNameBaseWith(outputName, usePic);

    CppCompileActionBuilder dBuilder = new CppCompileActionBuilder(builder);
    dBuilder.setOutputs(
        actionConstructionContext,
        ruleErrorConsumer,
        label,
        category,
        outputArtifactNameBase,
        generateDotd);
    dBuilder.setVariables(
        setupCompileBuildVariables(
            dBuilder,
            sourceLabel,
            usePic,
            ccRelativeName,
            ccCompilationContext.getCppModuleMap(),
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            ImmutableMap.of(
                CompileBuildVariables.OUTPUT_PREPROCESS_FILE.getVariableName(),
                dBuilder.getRealOutputFilePath().getSafePathString())));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, dBuilder);
    CppCompileAction dAction = dBuilder.buildOrThrowRuleError(ruleErrorConsumer);
    actionRegistry.registerAction(dAction);

    CppCompileActionBuilder sdBuilder = new CppCompileActionBuilder(builder);
    sdBuilder.setOutputs(
        actionConstructionContext,
        ruleErrorConsumer,
        label,
        ArtifactCategory.GENERATED_ASSEMBLY,
        outputArtifactNameBase,
        generateDotd);
    sdBuilder.setVariables(
        setupCompileBuildVariables(
            sdBuilder,
            sourceLabel,
            usePic,
            ccRelativeName,
            ccCompilationContext.getCppModuleMap(),
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            ImmutableMap.of(
                CompileBuildVariables.OUTPUT_ASSEMBLY_FILE.getVariableName(),
                sdBuilder.getRealOutputFilePath().getSafePathString())));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, sdBuilder);
    CppCompileAction sdAction = sdBuilder.buildOrThrowRuleError(ruleErrorConsumer);
    actionRegistry.registerAction(sdAction);

    return ImmutableList.of(dAction.getOutputFile(), sdAction.getOutputFile());
  }

  /**
   * Merges the STL and toolchain contexts into context builder. The STL is automatically determined
   * using the "$stl" (or, historically, ":stl") attribute.
   */
  private static void mergeToolchainDependentCcCompilationContext(
      CcToolchainProvider toolchain,
      CcCompilationContext.Builder ccCompilationContextBuilder) {
    if (toolchain != null) {
      ccCompilationContextBuilder.mergeDependentCcCompilationContext(
          toolchain.getCcCompilationContext());
    }
  }
}
