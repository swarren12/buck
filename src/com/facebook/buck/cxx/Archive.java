/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldDeps;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.Archiver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A {@link BuildRule} which builds an "ar" archive from input files represented as {@link
 * SourcePath}.
 */
public class Archive extends ModernBuildRule<Archive.Impl> {

  private final boolean cacheable;

  @VisibleForTesting
  Archive(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Archiver archiver,
      ImmutableList<Arg> archiverFlags,
      Optional<Tool> ranlib,
      ImmutableList<Arg> ranlibFlags,
      ArchiveContents contents,
      String outputFileName,
      ImmutableList<SourcePath> inputs,
      boolean cacheable,
      boolean withDownwardApi) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            archiver,
            archiverFlags,
            ranlib,
            ranlibFlags,
            contents,
            outputFileName,
            inputs,
            withDownwardApi));
    Preconditions.checkState(
        contents == ArchiveContents.NORMAL || archiver.supportsThinArchives(),
        "%s: archive tool for this platform does not support thin archives",
        getBuildTarget());
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "Static archive rule %s should not have any Linker Map Mode flavors",
        this);
    if (archiver.isRanLibStepRequired()) {
      Preconditions.checkArgument(ranlib.isPresent(), "ranlib is required");
    }
    this.cacheable = cacheable;
  }

  // TODO: msemko remove. Clients have to directly pass {@code withDownwardApi} param
  public static Archive from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      String outputFileName,
      ImmutableList<SourcePath> inputs,
      ArchiveContents contents,
      boolean cacheable) {
    return from(
        target,
        projectFilesystem,
        resolver,
        platform,
        outputFileName,
        inputs,
        contents,
        cacheable,
        false);
  }

  // TODO: msemko remove. Clients have to directly pass {@code withDownwardApi} param
  public static Archive from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      String outputFileName,
      ImmutableList<SourcePath> inputs) {
    return from(target, projectFilesystem, resolver, platform, outputFileName, inputs, false);
  }

  /** @return the {@link Archive} created from the given parameters. */
  public static Archive from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      String outputFileName,
      ImmutableList<SourcePath> inputs,
      ArchiveContents contents,
      boolean cacheable,
      boolean withDownwardApi) {
    return new Archive(
        target,
        projectFilesystem,
        resolver,
        platform.getAr().resolve(resolver, target.getTargetConfiguration()),
        platform.getArflags(),
        platform.getRanlib().map(r -> r.resolve(resolver, target.getTargetConfiguration())),
        platform.getRanlibflags(),
        contents,
        outputFileName,
        inputs,
        cacheable,
        withDownwardApi);
  }

  /** @return the {@link Archive} created from the given parameters. */
  public static Archive from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      String outputFileName,
      ImmutableList<SourcePath> inputs,
      boolean withDownwardApi) {
    return Archive.from(
        target,
        projectFilesystem,
        resolver,
        platform,
        outputFileName,
        inputs,
        platform.getArchiveContents(),
        true,
        withDownwardApi);
  }

  /** internal buildable implementation */
  static class Impl implements Buildable {

    @AddToRuleKey private final Archiver archiver;
    @AddToRuleKey private final ImmutableList<Arg> archiverFlags;
    @AddToRuleKey private final Optional<Tool> ranlib;
    @AddToRuleKey private final ImmutableList<Arg> ranlibFlags;
    @AddToRuleKey private final ArchiveContents contents;
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final ImmutableList<SourcePath> inputs;

    @ExcludeFromRuleKey(
        reason = "downward API doesn't affect the result of rule's execution",
        serialization = DefaultFieldSerialization.class,
        inputs = DefaultFieldInputs.class,
        deps = DefaultFieldDeps.class)
    private final boolean withDownwardApi;

    Impl(
        Archiver archiver,
        ImmutableList<Arg> archiverFlags,
        Optional<Tool> ranlib,
        ImmutableList<Arg> ranlibFlags,
        ArchiveContents contents,
        String outputFileName,
        ImmutableList<SourcePath> inputs,
        boolean withDownwardApi) {
      this.archiver = archiver;
      this.archiverFlags = archiverFlags;
      this.ranlib = ranlib;
      this.ranlibFlags = ranlibFlags;
      this.contents = contents;
      this.output = new OutputPath(outputFileName);
      this.inputs = inputs;
      this.withDownwardApi = withDownwardApi;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();

      // We only support packaging inputs that use the same filesystem root as the output, as thin
      // archives embed relative paths from output to input inside the archive.  If this becomes a
      // limitation, we could make this rule uncacheable and allow thin archives to embed absolute
      // paths.
      AbsPath rootPath = filesystem.getRootPath();
      for (SourcePath input : inputs) {
        Preconditions.checkState(resolver.getFilesystem(input).getRootPath().equals(rootPath));
      }

      ImmutableList.Builder<Step> builder = ImmutableList.builder();
      Path outputPath = outputPathResolver.resolvePath(output);
      builder
          .add(MkdirStep.of(buildCellPathFactory.from(outputPath.getParent())))
          .add(
              new ArchiveStep(
                  filesystem,
                  archiver.getEnvironment(resolver),
                  archiver.getCommandPrefix(resolver),
                  Arg.stringify(archiverFlags, resolver),
                  archiver.getArchiveOptions(contents == ArchiveContents.THIN),
                  outputPath,
                  inputs.stream()
                      .map(resolver::getRelativePath)
                      .collect(ImmutableList.toImmutableList()),
                  archiver,
                  outputPathResolver.getTempPath(),
                  withDownwardApi));

      if (archiver.isRanLibStepRequired()) {
        Tool tool = ranlib.get();
        builder.add(
            new RanlibStep(
                filesystem,
                tool.getEnvironment(resolver),
                tool.getCommandPrefix(resolver),
                Arg.stringify(ranlibFlags, resolver),
                outputPath,
                withDownwardApi));
      }

      if (!archiver.getScrubbers().isEmpty()) {
        builder.add(new FileScrubberStep(filesystem, outputPath, archiver.getScrubbers()));
      }

      return builder.build();
    }

    @VisibleForTesting
    ArchiveContents getContents() {
      return contents;
    }
  }

  /**
   * @return the {@link Arg} to use when using this archive. When thin archives are used, this will
   *     ensure that the inputs are also propagated as build time deps to whatever rule uses this
   *     archive.
   */
  public Arg toArg() {
    SourcePath archive = getSourcePathToOutput();
    return getBuildable().contents == ArchiveContents.NORMAL
        ? SourcePathArg.of(archive)
        : ImmutableThinArchiveArg.ofImpl(archive, getBuildable().inputs);
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }
}
