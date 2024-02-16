// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.runfiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Runfiles lookup library for Bazel-built Java binaries and tests.
 *
 * <p>USAGE:
 *
 * <p>1. Depend on this runfiles library from your build rule:
 *
 * <pre>
 *   java_binary(
 *       name = "my_binary",
 *       ...
 *       deps = ["@bazel_tools//tools/java/runfiles"],
 *   )
 * </pre>
 *
 * <p>2. Import the runfiles library.
 *
 * <pre>
 *   import com.google.devtools.build.runfiles.Runfiles;
 * </pre>
 *
 * <p>3. Create a {@link Preloaded} object:
 *
 * <pre>
 *   public void myFunction() {
 *     Runfiles.Preloaded runfiles = Runfiles.preload();
 *     ...
 * </pre>
 *
 * <p>4. To look up a runfile, use either of the following approaches:
 *
 * <p>4a. Annotate the class from which runfiles should be looked up with {@link
 * AutoBazelRepository} and obtain the name of the Bazel repository containing the class from a
 * constant generated by this annotation:
 *
 * <pre>
 *   import com.google.devtools.build.runfiles.AutoBazelRepository;
 *   &#64;AutoBazelRepository
 *   public class MyClass {
 *     public void myFunction() {
 *       Runfiles.Preloaded runfiles = Runfiles.preload();
 *       String path = runfiles.withSourceRepository(AutoBazelRepository_MyClass.NAME)
 *                             .rlocation("my_workspace/path/to/my/data.txt");
 *       ...
 *
 * </pre>
 *
 * <p>4b. Let Bazel compute the path passed to rlocation and pass it into a <code>java_binary</code>
 * via an argument or an environment variable:
 *
 * <pre>
 *   java_binary(
 *       name = "my_binary",
 *       srcs = ["MyClass.java"],
 *       data = ["@my_workspace//path/to/my:data.txt"],
 *       env = {"MY_RUNFILE": "$(rlocationpath @my_workspace//path/to/my:data.txt)"},
 *   )
 * </pre>
 *
 * <pre>
 *   public class MyClass {
 *     public void myFunction() {
 *       Runfiles.Preloaded runfiles = Runfiles.preload();
 *       String path = runfiles.unmapped().rlocation(System.getenv("MY_RUNFILE"));
 *       ...
 *
 * </pre>
 *
 * For more details on why it is required to pass in the current repository name, see {@see
 * https://bazel.build/build/bzlmod#repository-names}.
 *
 * <h3>Subprocesses</h3>
 *
 * <p>If you want to start subprocesses that also need runfiles, you need to set the right
 * environment variables for them:
 *
 * <pre>
 *   String path = r.rlocation("path/to/binary");
 *   ProcessBuilder pb = new ProcessBuilder(path);
 *   pb.environment().putAll(r.getEnvVars());
 *   ...
 *   Process p = pb.start();
 * </pre>
 *
 * <h3>{@link Preloaded} vs. {@link Runfiles}</h3>
 *
 * <p>Instances of {@link Preloaded} are meant to be stored and passed around to other components
 * that need to access runfiles. They are created by calling {@link Runfiles#preload()} {@link
 * Runfiles#preload(Map)} and immutably encapsulate all data required to look up runfiles with the
 * repository mapping of any Bazel repository specified at a later time.
 *
 * <p>Creating {@link Runfiles} instances can be costly, so applications should try to create as few
 * instances as possible. {@link Runfiles#preload()}, but not {@link Runfiles#preload(Map)}, returns
 * a single global, softly cached instance of {@link Preloaded} that is constructed based on the
 * JVM's environment variables.
 *
 * <p>Instance of {@link Runfiles} are only meant to be used by code located in a single Bazel
 * repository and should not be passed around. They are created by calling {@link
 * Preloaded#withSourceRepository(String)} or {@link Preloaded#unmapped()} and in addition to the
 * data in {@link Preloaded} also fix a source repository relative to which apparent repository
 * names are resolved.
 *
 * <p>Creating {@link Preloaded} instances is cheap.
 */
public final class Runfiles {

  /**
   * A class that encapsulates all data required to look up runfiles relative to any Bazel
   * repository fixed at a later time.
   *
   * <p>This class is immutable.
   */
  public abstract static class Preloaded {

    /** See {@link com.google.devtools.build.lib.analysis.RepoMappingManifestAction.Entry}. */
    static class RepoMappingKey {

      public final String sourceRepo;
      public final String targetRepoApparentName;

      public RepoMappingKey(String sourceRepo, String targetRepoApparentName) {
        this.sourceRepo = sourceRepo;
        this.targetRepoApparentName = targetRepoApparentName;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || !(o instanceof RepoMappingKey)) {
          return false;
        }
        RepoMappingKey that = (RepoMappingKey) o;
        return sourceRepo.equals(that.sourceRepo)
            && targetRepoApparentName.equals(that.targetRepoApparentName);
      }

      @Override
      public int hashCode() {
        return Objects.hash(sourceRepo, targetRepoApparentName);
      }
    }

    /**
     * Returns a {@link Runfiles} instance that uses the provided source repository's repository
     * mapping to translate apparent into canonical repository names.
     *
     * <p>{@see https://bazel.build/build/bzlmod#repository-names}
     *
     * @param sourceRepository the canonical name of the Bazel repository relative to which apparent
     *     repository names should be resolved. Should generally coincide with the Bazel repository
     *     that contains the caller of this method, which can be obtained via {@link
     *     AutoBazelRepository}.
     * @return a {@link Runfiles} instance that looks up runfiles relative to the provided source
     *     repository and shares all other data with this {@link Preloaded} instance.
     */
    public final Runfiles withSourceRepository(String sourceRepository) {
      Util.checkArgument(sourceRepository != null);
      return new Runfiles(this, sourceRepository);
    }

    /**
     * Returns a {@link Runfiles} instance backed by the preloaded runfiles data that can be used to
     * look up runfiles paths with canonical repository names only.
     *
     * @return a {@link Runfiles} instance that can only look up paths with canonical repository
     *     names and shared all data with this {@link Preloaded} instance.
     */
    public final Runfiles unmapped() {
      return new Runfiles(this, null);
    }

    protected abstract Map<String, String> getEnvVars();

    protected abstract String rlocationChecked(String path);

    protected abstract Map<RepoMappingKey, String> getRepoMapping();

    // Private constructor, so only nested classes may extend it.
    private Preloaded() {}
  }

  private static final String MAIN_REPOSITORY = "";

  private static SoftReference<Preloaded> defaultInstance = new SoftReference<>(null);

  private final Preloaded preloadedRunfiles;
  private final String sourceRepository;

  private Runfiles(Preloaded preloadedRunfiles, String sourceRepository) {
    this.preloadedRunfiles = preloadedRunfiles;
    this.sourceRepository = sourceRepository;
  }

  /**
   * Returns the softly cached global {@link Runfiles.Preloaded} instance, creating it if needed.
   *
   * <p>This method passes the JVM's environment variable map to {@link #create(Map)}.
   */
  public static synchronized Preloaded preload() throws IOException {
    Preloaded instance = defaultInstance.get();
    if (instance != null) {
      return instance;
    }
    instance = preload(System.getenv());
    defaultInstance = new SoftReference<>(instance);
    return instance;
  }

  /**
   * Returns a new {@link Runfiles.Preloaded} instance.
   *
   * <p>The returned object is either:
   *
   * <ul>
   *   <li>manifest-based, meaning it looks up runfile paths from a manifest file, or
   *   <li>directory-based, meaning it looks up runfile paths under a given directory path
   * </ul>
   *
   * <p>If {@code env} contains "RUNFILES_MANIFEST_ONLY" with value "1", this method returns a
   * manifest-based implementation. The manifest's path is defined by the "RUNFILES_MANIFEST_FILE"
   * key's value in {@code env}.
   *
   * <p>Otherwise this method returns a directory-based implementation. The directory's path is
   * defined by the value in {@code env} under the "RUNFILES_DIR" key, or if absent, then under the
   * "JAVA_RUNFILES" key.
   *
   * <p>Note about performance: the manifest-based implementation eagerly reads and caches the whole
   * manifest file upon instantiation.
   *
   * @throws IOException if RUNFILES_MANIFEST_ONLY=1 is in {@code env} but there's no
   *     "RUNFILES_MANIFEST_FILE", "RUNFILES_DIR", or "JAVA_RUNFILES" key in {@code env} or their
   *     values are empty, or some IO error occurs
   */
  public static Preloaded preload(Map<String, String> env) throws IOException {
    String runfilesDir = getRunfilesDir(env);
    // Prefer RUNFILES_DIR if available since it is faster.
    if (!Util.isNullOrEmpty(runfilesDir)
        && Files.exists(Paths.get(runfilesDir, "_runfiles_enabled"))) {
      return new DirectoryBased(runfilesDir);
    } else {
      return new ManifestBased(getManifestPath(env));
    }
  }

  /**
   * Returns a new {@link Runfiles} instance.
   *
   * <p>This method passes the JVM's environment variable map to {@link #create(Map)}.
   *
   * @deprecated Use {@link #preload()} instead. With {@code --enable_bzlmod}, this function does
   *     not work correctly.
   */
  @Deprecated
  public static Runfiles create() throws IOException {
    return preload().withSourceRepository(MAIN_REPOSITORY);
  }

  /**
   * Returns a new {@link Runfiles} instance.
   *
   * <p>The returned object is either:
   *
   * <ul>
   *   <li>manifest-based, meaning it looks up runfile paths from a manifest file, or
   *   <li>directory-based, meaning it looks up runfile paths under a given directory path
   * </ul>
   *
   * <p>If {@code env} contains "RUNFILES_MANIFEST_ONLY" with value "1", this method returns a
   * manifest-based implementation. The manifest's path is defined by the "RUNFILES_MANIFEST_FILE"
   * key's value in {@code env}.
   *
   * <p>Otherwise this method returns a directory-based implementation. The directory's path is
   * defined by the value in {@code env} under the "RUNFILES_DIR" key, or if absent, then under the
   * "JAVA_RUNFILES" key.
   *
   * <p>Note about performance: the manifest-based implementation eagerly reads and caches the whole
   * manifest file upon instantiation.
   *
   * @throws IOException if RUNFILES_MANIFEST_ONLY=1 is in {@code env} but there's no
   *     "RUNFILES_MANIFEST_FILE", "RUNFILES_DIR", or "JAVA_RUNFILES" key in {@code env} or their
   *     values are empty, or some IO error occurs
   * @deprecated Use {@link #preload(Map)} instead. With {@code --enable_bzlmod}, this function does
   *     not work correctly.
   */
  @Deprecated
  public static Runfiles create(Map<String, String> env) throws IOException {
    return preload(env).withSourceRepository(MAIN_REPOSITORY);
  }

  /**
   * Returns the runtime path of a runfile (a Bazel-built binary's/test's data-dependency).
   *
   * <p>The returned path may not be valid. The caller should check the path's validity and that the
   * path exists.
   *
   * <p>The function may return null. In that case the caller can be sure that the rule does not
   * know about this data-dependency.
   *
   * @param path runfiles-root-relative path of the runfile
   * @throws IllegalArgumentException if {@code path} fails validation, for example if it's null or
   *     empty, or not normalized (contains "./", "../", or "//")
   */
  public String rlocation(String path) {
    Util.checkArgument(path != null);
    Util.checkArgument(!path.isEmpty());
    Util.checkArgument(
        !path.startsWith("../")
            && !path.contains("/..")
            && !path.startsWith("./")
            && !path.contains("/./")
            && !path.endsWith("/.")
            && !path.contains("//"),
        "path is not normalized: \"%s\"",
        path);
    Util.checkArgument(
        !path.startsWith("\\"), "path is absolute without a drive letter: \"%s\"", path);
    if (new File(path).isAbsolute()) {
      return path;
    }

    if (sourceRepository == null) {
      return preloadedRunfiles.rlocationChecked(path);
    }
    String[] apparentTargetAndRemainder = path.split("/", 2);
    if (apparentTargetAndRemainder.length < 2) {
      return preloadedRunfiles.rlocationChecked(path);
    }
    String targetCanonical = getCanonicalRepositoryName(apparentTargetAndRemainder[0]);
    return preloadedRunfiles.rlocationChecked(
        targetCanonical + "/" + apparentTargetAndRemainder[1]);
  }

  /**
   * Returns environment variables for subprocesses.
   *
   * <p>The caller should add the returned key-value pairs to the environment of subprocesses in
   * case those subprocesses are also Bazel-built binaries that need to use runfiles.
   */
  public Map<String, String> getEnvVars() {
    return preloadedRunfiles.getEnvVars();
  }

  String getCanonicalRepositoryName(String apparentRepositoryName) {
    return preloadedRunfiles
        .getRepoMapping()
        .getOrDefault(
            new Preloaded.RepoMappingKey(sourceRepository, apparentRepositoryName),
            apparentRepositoryName);
  }

  private static String getManifestPath(Map<String, String> env) throws IOException {
    String value = env.get("RUNFILES_MANIFEST_FILE");
    if (Util.isNullOrEmpty(value)) {
      throw new IOException(
          "Cannot load runfiles manifest: $RUNFILES_DIR and $JAVA_RUNFILES do not exist or are not populated and $RUNFILES_MANIFEST_FILE is empty or undefined");
    }
    return value;
  }

  private static String getRunfilesDir(Map<String, String> env) {
    String value = env.get("RUNFILES_DIR");
    if (Util.isNullOrEmpty(value)) {
      value = env.get("JAVA_RUNFILES");
    }
    return value;
  }

  private static Map<Preloaded.RepoMappingKey, String> loadRepositoryMapping(String path)
      throws IOException {
    if (path == null || !new File(path).exists()) {
      return Collections.emptyMap();
    }

    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
      return Collections.unmodifiableMap(
          r.lines()
              .filter(line -> !line.isEmpty())
              .map(
                  line -> {
                    String[] split = line.split(",");
                    if (split.length != 3) {
                      throw new IllegalArgumentException(
                          "Invalid line in repository mapping: '" + line + "'");
                    }
                    return split;
                  })
              .collect(
                  Collectors.toMap(
                      split -> new Preloaded.RepoMappingKey(split[0], split[1]),
                      split -> split[2])));
    }
  }

  /** {@link Runfiles} implementation that parses a runfiles-manifest file to look up runfiles. */
  private static final class ManifestBased extends Runfiles.Preloaded {

    private final Map<String, String> runfiles;
    private final String manifestPath;
    private final Map<RepoMappingKey, String> repoMapping;

    ManifestBased(String manifestPath) throws IOException {
      Util.checkArgument(manifestPath != null);
      Util.checkArgument(!manifestPath.isEmpty());
      this.manifestPath = manifestPath;
      this.runfiles = loadRunfiles(manifestPath);
      this.repoMapping = loadRepositoryMapping(rlocationChecked("_repo_mapping"));
    }

    @Override
    protected String rlocationChecked(String path) {
      String exactMatch = runfiles.get(path);
      if (exactMatch != null) {
        return exactMatch;
      }
      // If path references a runfile that lies under a directory that itself is a runfile, then
      // only the directory is listed in the manifest. Look up all prefixes of path in the manifest
      // and append the relative path from the prefix if there is a match.
      int prefixEnd = path.length();
      while ((prefixEnd = path.lastIndexOf('/', prefixEnd - 1)) != -1) {
        String prefixMatch = runfiles.get(path.substring(0, prefixEnd));
        if (prefixMatch != null) {
          return prefixMatch + '/' + path.substring(prefixEnd + 1);
        }
      }
      return null;
    }

    @Override
    protected Map<String, String> getEnvVars() {
      HashMap<String, String> result = new HashMap<>(4);
      result.put("RUNFILES_MANIFEST_ONLY", "1");
      result.put("RUNFILES_MANIFEST_FILE", manifestPath);
      String runfilesDir = findRunfilesDir(manifestPath);
      result.put("RUNFILES_DIR", runfilesDir);
      // TODO(laszlocsomor): remove JAVA_RUNFILES once the Java launcher can pick up RUNFILES_DIR.
      result.put("JAVA_RUNFILES", runfilesDir);
      return result;
    }

    @Override
    protected Map<RepoMappingKey, String> getRepoMapping() {
      return repoMapping;
    }

    private static Map<String, String> loadRunfiles(String path) throws IOException {
      HashMap<String, String> result = new HashMap<>();
      try (BufferedReader r =
          new BufferedReader(
              new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
        String line = null;
        while ((line = r.readLine()) != null) {
          int index = line.indexOf(' ');
          String runfile = (index == -1) ? line : line.substring(0, index);
          String realPath = (index == -1) ? line : line.substring(index + 1);
          result.put(runfile, realPath);
        }
      }
      return Collections.unmodifiableMap(result);
    }

    private static String findRunfilesDir(String manifest) {
      if (manifest.endsWith("/MANIFEST")
          || manifest.endsWith("\\MANIFEST")
          || manifest.endsWith(".runfiles_manifest")) {
        String path = manifest.substring(0, manifest.length() - 9);
        if (new File(path).isDirectory()) {
          return path;
        }
      }
      return "";
    }
  }

  /** {@link Runfiles} implementation that appends runfiles paths to the runfiles root. */
  private static final class DirectoryBased extends Preloaded {

    private final String runfilesRoot;
    private final Map<RepoMappingKey, String> repoMapping;

    DirectoryBased(String runfilesDir) throws IOException {
      Util.checkArgument(!Util.isNullOrEmpty(runfilesDir));
      Util.checkArgument(new File(runfilesDir).isDirectory());
      this.runfilesRoot = runfilesDir;
      this.repoMapping = loadRepositoryMapping(rlocationChecked("_repo_mapping"));
    }

    @Override
    protected String rlocationChecked(String path) {
      return runfilesRoot + "/" + path;
    }

    @Override
    protected Map<RepoMappingKey, String> getRepoMapping() {
      return repoMapping;
    }

    @Override
    protected Map<String, String> getEnvVars() {
      HashMap<String, String> result = new HashMap<>(2);
      result.put("RUNFILES_DIR", runfilesRoot);
      // TODO(laszlocsomor): remove JAVA_RUNFILES once the Java launcher can pick up RUNFILES_DIR.
      result.put("JAVA_RUNFILES", runfilesRoot);
      return result;
    }
  }

  static Preloaded createManifestBasedForTesting(String manifestPath) throws IOException {
    return new ManifestBased(manifestPath);
  }

  static Preloaded createDirectoryBasedForTesting(String runfilesDir) throws IOException {
    return new DirectoryBased(runfilesDir);
  }
}
