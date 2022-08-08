package com.google.devtools.build.android;

import android.databinding.AndroidDataBinding;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.devtools.build.android.AndroidResourceProcessor.AaptConfigOptions;
import com.google.devtools.build.android.Converters.PathConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.ShellQuotedParamsFilePreProcessor;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

public class GenerateDatabindingBaseClassesAction {

  public static final class Options extends OptionsBase {
    @Option(
        name = "layoutInfoFiles",
        defaultValue = "null",
        converter = PathConverter.class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "Path to layout-info.zip file produced by databinding processor")
    public Path layoutInfoFile;

    @Option(
        name = "package",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "Package name of the android target")
    public String packageName;

    @Option(
        name = "classInfoOut",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        defaultValue = "null",
        converter = PathConverter.class,
        category = "output",
        help = "Path to write classInfo.zip file")
    public Path classInfoOut;

    @Option(
        name = "sourceOut",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        defaultValue = "null",
        converter = PathConverter.class,
        category = "output",
        help = "Path to write databinding base classes to be used in Java compilation")
    public Path sourceOut;

    @Option(
        name = "dependencyClassInfoList",
        defaultValue = "null",
        converter = PathConverter.class,
        allowMultiple = true,
        category = "input",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "List of dependency class info zip files")
    public List<Path> dependencyClassInfoList;
  }

  static final Logger logger =
      Logger.getLogger(GenerateDatabindingBaseClassesAction.class.getName());

  public static void main(String[] args) throws Exception {
    final OptionsParser optionsParser =
        OptionsParser.builder()
            .allowResidue(true)
            .optionsClasses(
                Options.class, AaptConfigOptions.class, ResourceProcessorCommonOptions.class)
            .argsPreProcessor(new ShellQuotedParamsFilePreProcessor(FileSystems.getDefault()))
            .build();
    optionsParser.parseAndExitUponError(args);
    final Options options = optionsParser.getOptions(Options.class);
    final AaptConfigOptions aaptConfigOptions = optionsParser.getOptions(AaptConfigOptions.class);

    if (options.layoutInfoFile == null) {
      throw new IllegalArgumentException("--layoutInfoFiles is required");
    }

    if (options.packageName == null) {
      throw new IllegalArgumentException("--packageName is required");
    }

    if (options.classInfoOut == null) {
      throw new IllegalArgumentException("--classInfoOut is required");
    }

    if (options.sourceOut == null) {
      throw new IllegalArgumentException("--sourceOut is required");
    }

    final List<Path> dependencyClassInfoList =
        options.dependencyClassInfoList == null
            ? Collections.emptyList()
            : options.dependencyClassInfoList;

    final ImmutableList.Builder<String> dbArgsBuilder =
        ImmutableList.<String>builder()
            .add("GEN_BASE_CLASSES")
            .add("-layoutInfoFiles")
            .add(options.layoutInfoFile.toString())
            .add("-package", options.packageName)
            .add("-classInfoOut")
            .add(options.classInfoOut.toString())
            .add("-sourceOut")
            .add(options.sourceOut.toString())
            .add("-zipSourceOutput")
            .add("true")
            .add("-useAndroidX")
            .add(Boolean.toString(aaptConfigOptions.useDataBindingAndroidX));

    dependencyClassInfoList.forEach(
        classInfo -> dbArgsBuilder.add("-dependencyClassInfoList").add(classInfo.toString()));

    try {
      AndroidDataBinding.main(
          Streams.mapWithIndex(
                  dbArgsBuilder.build().stream(), (arg, index) -> index == 0 ? arg : arg + " ")
              .toArray(String[]::new));
    } catch (Exception e) {
      logger.log(java.util.logging.Level.SEVERE, "Unexpected", e);
      throw e;
    }
  }
}
