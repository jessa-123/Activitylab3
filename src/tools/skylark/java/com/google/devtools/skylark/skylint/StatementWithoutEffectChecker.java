// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.skylark.skylint;

import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Expression;
import com.google.devtools.build.lib.syntax.ExpressionStatement;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionDefStatement;
import com.google.devtools.build.lib.syntax.ListComprehension;
import com.google.devtools.build.lib.syntax.Statement;
import com.google.devtools.build.lib.syntax.StringLiteral;
import com.google.devtools.build.lib.syntax.SyntaxTreeVisitor;
import java.util.ArrayList;
import java.util.List;

/** Checks for statements that have no effect. */
public class StatementWithoutEffectChecker extends SyntaxTreeVisitor {
  private final List<Issue> issues = new ArrayList<>();
  private boolean hasEffect = false;
  private boolean topLevel = true;

  public static List<Issue> check(BuildFileAST ast) {
    StatementWithoutEffectChecker checker = new StatementWithoutEffectChecker();
    checker.visit(ast);
    return checker.issues;
  }

  @Override
  public void visit(BuildFileAST ast) {
    checkStatementsExceptDocstring(ast.getStatements());
  }

  @Override
  public void visit(FunctionDefStatement node) {
    topLevel = false;
    checkStatementsExceptDocstring(node.getStatements());
    topLevel = true;
  }

  private void checkStatementsExceptDocstring(List<Statement> stmts) {
    if (stmts.isEmpty()) {
      return;
    }
    Statement firstStatement = stmts.get(0);
    // skip docstrings
    boolean skipFirstStatement =
        firstStatement instanceof ExpressionStatement
            && ((ExpressionStatement) firstStatement).getExpression() instanceof StringLiteral;
    for (int i = skipFirstStatement ? 1 : 0; i < stmts.size(); i++) {
      visit(stmts.get(i));
    }
  }

  @Override
  public void visit(ExpressionStatement node) {
    hasEffect = false;
    super.visit(node);
    Expression expr = node.getExpression();
    if (expr instanceof FuncallExpression) {
      return; // function calls can have an effect
    }
    if (expr instanceof ListComprehension && topLevel && hasEffect) {
      // allow list comprehensions at the top level if they have an effect, e.g. [print(x) for x in
      // list]
      return;
    }
    issues.add(new Issue("expression result not used", node.getLocation()));
  }

  @Override
  public void visit(FuncallExpression node) {
    hasEffect = true;
  }
}
