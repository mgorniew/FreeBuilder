/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.inferred.freebuilder.processor.util;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Lists.newArrayList;

import com.google.common.annotations.VisibleForTesting;
import com.google.googlejavaformat.java.Formatter;

import org.inferred.freebuilder.processor.util.Scope.FileScope;
import org.inferred.freebuilder.processor.util.feature.FeatureSet;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;

/** {@code SourceBuilder} which also handles package declaration and imports. */
public class CompilationUnitBuilder
    extends AbstractSourceBuilder<CompilationUnitBuilder>
    implements SourceParser.EventHandler {

  private final ImportManager importManager;
  private final QualifiedName classToWrite;
  private final ScopeHandler scopeHandler;
  private final SourceParser parser;
  private final List<ScopeAwareTypeShortener> typeShorteners = newArrayList();
  private final StringBuilder source = new StringBuilder();

  /**
   * Returns a {@link CompilationUnitBuilder} for {@code classToWrite} using {@code features}. The
   * file preamble (package and imports) will be generated automatically, and {@code env} will be
   * inspected for potential import collisions.
   */
  public CompilationUnitBuilder(
      ProcessingEnvironment env,
      QualifiedName classToWrite,
      Collection<QualifiedName> implicitImports,
      FeatureSet features) {
    super(features, new FileScope());
    this.classToWrite = classToWrite;
    // Write the source code into an intermediate SourceStringBuilder, as the imports need to be
    // written first, but aren't known yet.
    scopeHandler = new ScopeHandler(env.getElementUtils());
    scopeHandler.predeclareGeneratedType(classToWrite);
    for (QualifiedName implicitImport : implicitImports) {
      if (implicitImport.isNestedIn(classToWrite)) {
        scopeHandler.predeclareGeneratedType(implicitImport);
      }
    }
    importManager = new ImportManager();
    parser = new SourceParser(this);
    typeShorteners.add(new ScopeAwareTypeShortener(
        importManager, scopeHandler, classToWrite.getPackage()));
  }

  @Override
  public void onTypeBlockStart(String keyword, String simpleName, Set<String> supertypes) {
    ScopeAwareTypeShortener typeShortener = getLast(typeShorteners).inScope(simpleName, supertypes);
    typeShorteners.add(typeShortener);
  }

  @Override
  public void onOtherBlockStart() {
    typeShorteners.add(getLast(typeShorteners));
  }

  @Override
  public void onBlockEnd() {
    typeShorteners.remove(typeShorteners.size() - 1);
    checkState(!typeShorteners.isEmpty(), "Unexpected '}'");
  }

  @Override
  protected CompilationUnitBuilder getThis() {
    return this;
  }

  @Override
  public CompilationUnitBuilder append(char c) {
    source.append(c);
    parser.parse(c);
    return this;
  }

  @Override
  protected TypeShortener getShortener() {
    return getLast(typeShorteners);
  }

  @Override
  public String toString() {
    StringBuilder unit = new StringBuilder();
    unit.append("// Autogenerated code. Do not modify.\n")
        .append("package ").append(classToWrite.getPackage()).append(";\n")
        .append("\n");
    if (!importManager.getClassImports().isEmpty()) {
      for (String classImport : importManager.getClassImports()) {
        unit.append("import ").append(classImport).append(";\n");
      }
      unit.append("\n");
    }
    unit.append(formatSource(source.toString()));
    return unit.toString();
  }

  @VisibleForTesting
  public static String formatSource(String source) {
    try {
      return new Formatter().formatSource(source);
    } catch (UnsupportedClassVersionError e) {
      // Formatter requires Java 7+; do no formatting in Java 6.
      return source;
    } catch (Exception e) {
      StringBuilder message = new StringBuilder()
          .append("Formatter failed:\n")
          .append(e.getMessage())
          .append("\nGenerated source:");
      int lineNo = 0;
      for (String line : source.split("\n")) {
        message
            .append("\n")
            .append(++lineNo)
            .append(": ")
            .append(line);
      }
      throw new RuntimeException(message.toString());
    }
  }
}
