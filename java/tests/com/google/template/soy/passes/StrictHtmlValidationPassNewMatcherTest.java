/*
 * Copyright 2018 Google Inc.
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

package com.google.template.soy.passes;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.base.internal.IncrementingIdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.passes.PassManager.PassContinuationRule;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherAccumulatorNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraph;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode.EdgeKind;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherIfConditionNode;
import com.google.template.soy.passes.htmlmatcher.TestUtils;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.SoyFileNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that build an HTML Matcher graph and validates it. */
@RunWith(JUnit4.class)
public final class StrictHtmlValidationPassNewMatcherTest {

  @Test
  public void testEmptyTemplate() {
    // Arrange: set up an empty template.
    SoyFileNode template = parseTemplateBody("");
    StrictHtmlValidationPassNewMatcher matcherPass =
        new StrictHtmlValidationPassNewMatcher(ErrorReporter.exploding());

    // Act: execute the graph builder.
    matcherPass.run(template, new IncrementingIdGenerator());
    Optional<HtmlMatcherGraph> matcherGraph = matcherPass.getHtmlMatcherGraph();

    // Assert.
    assertThat(matcherGraph).isPresent();
    assertThat(matcherGraph.get().getRootNode()).isAbsent();
  }

  @Test
  public void testSimpleTemplate() {
    // Arrange: set up a simple template.
    SoyFileNode template = parseTemplateBody(Joiner.on("\n").join("<div>", "</div>"));
    StrictHtmlValidationPassNewMatcher matcherPass =
        new StrictHtmlValidationPassNewMatcher(ErrorReporter.exploding());

    // Act: execute the graph builder
    matcherPass.run(template, new IncrementingIdGenerator());
    Optional<HtmlMatcherGraph> matcherGraph = matcherPass.getHtmlMatcherGraph();

    // Assert.
    HtmlMatcherGraphNode node = matcherGraph.get().getRootNode().get();
    TestUtils.assertNodeIsOpenTagWithName(node, "div");
    TestUtils.assertNodeIsCloseTagWithName(
        node.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get(), "div");
  }

  @Test
  public void testTextOnlyIfBranch() {
    // Arrange: set up a template with a text-only {if $cond1} branch.
    SoyFileNode template =
        parseTemplateBody(
            Joiner.on("\n")
                .join("{@param cond1: bool}", "<span>", "  {if $cond1}Content1{/if}", "</span>"));
    StrictHtmlValidationPassNewMatcher matcherPass =
        new StrictHtmlValidationPassNewMatcher(ErrorReporter.exploding());

    // Act: execute the graph builder.
    matcherPass.run(template, new IncrementingIdGenerator());
    Optional<HtmlMatcherGraph> matcherGraph = matcherPass.getHtmlMatcherGraph();

    // Follow the HTML matcher graph and validate the structure.
    HtmlMatcherGraphNode node = matcherGraph.get().getRootNode().get();

    // The root node should be a opening <span> tag.
    TestUtils.assertNodeIsOpenTagWithName(node, "span");

    // The next node should be {if $cond1}.
    HtmlMatcherGraphNode ifCondNode = node.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(ifCondNode).isInstanceOf(HtmlMatcherIfConditionNode.class);

    HtmlMatcherGraphNode accNode = ifCondNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(accNode).isInstanceOf(HtmlMatcherAccumulatorNode.class);

    // Follow the {@code true} edge. The next node should be the </div>
    node = accNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsCloseTagWithName(node, "span");

    // Follow the graph along the false edge from the if condition node.
    assertThat(ifCondNode.getNodeForEdgeKind(EdgeKind.FALSE_EDGE)).hasValue(accNode);
  }

  @Test
  public void testSingleBranchIfCondition() {
    // Arrange: set up a template with a {if $cond1} branch that contains an element.
    SoyFileNode template =
        parseTemplateBody(
            Joiner.on("\n")
                .join(
                    "{@param cond1: bool}",
                    "<div>",
                    "  {if $cond1}</div><div>Content1{/if}",
                    "</div>"));
    StrictHtmlValidationPassNewMatcher matcherPass =
        new StrictHtmlValidationPassNewMatcher(ErrorReporter.exploding());

    // Act: execute the graph builder.
    matcherPass.run(template, new IncrementingIdGenerator());
    Optional<HtmlMatcherGraph> matcherGraph = matcherPass.getHtmlMatcherGraph();

    // Assert: follow the graph and validate its structure.

    // The root node should be the first <div>.
    HtmlMatcherGraphNode rootNode = matcherGraph.get().getRootNode().get();
    TestUtils.assertNodeIsOpenTagWithName(rootNode, "div");

    // The next node should be the {if $cond1}.
    HtmlMatcherGraphNode ifConditionNode = rootNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(ifConditionNode).isInstanceOf(HtmlMatcherIfConditionNode.class);
    assertThatIfExpressionEqualTo((HtmlMatcherIfConditionNode) ifConditionNode, "$cond1");

    // Follow the {@code true} edge. The next node should be the </div>
    HtmlMatcherGraphNode nextNode = ifConditionNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsCloseTagWithName(nextNode, "div");

    // The next node should be another <div>
    nextNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsOpenTagWithName(nextNode, "div");

    // The next node should be an accumulator node, which closes the true branch.
    HtmlMatcherGraphNode accNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(accNode).isInstanceOf(HtmlMatcherAccumulatorNode.class);

    // Follow the false branch of the initial {if $cond1} node. This should link directly to the
    // accumulator node.
    assertThat(ifConditionNode.getNodeForEdgeKind(EdgeKind.FALSE_EDGE)).hasValue(accNode);

    // The next node should be the final </div>.
    nextNode = accNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsCloseTagWithName(nextNode, "div");

    // Verify that the graph ends here.
    assertThat(nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE)).isAbsent();
  }

  @Test
  public void testTwoIfConditions() {
    // Arrange: set up the template under test.
    SoyFileNode template =
        parseTemplateBody(
            Joiner.on("\n")
                .join(
                    "{@param cond1: bool}",
                    "{@param cond2: bool}",
                    "<div>",
                    "  {if $cond1}<div>Content1{/if}",
                    "  {if $cond2}<div>Content2{/if}",
                    "</div>",
                    "</div>"));
    StrictHtmlValidationPassNewMatcher matcherPass =
        new StrictHtmlValidationPassNewMatcher(ErrorReporter.exploding());

    // Act: execute the graph builder.
    matcherPass.run(template, new IncrementingIdGenerator());
    Optional<HtmlMatcherGraph> matcherGraph = matcherPass.getHtmlMatcherGraph();

    // Assert: follow the graph and validate its structure.

    // The root node should be the first <div>.
    HtmlMatcherGraphNode rootNode = matcherGraph.get().getRootNode().get();
    TestUtils.assertNodeIsOpenTagWithName(rootNode, "div");

    // The next node should be the {if $cond1}.
    HtmlMatcherGraphNode ifConditionNode = rootNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(ifConditionNode).isInstanceOf(HtmlMatcherIfConditionNode.class);
    assertThatIfExpressionEqualTo((HtmlMatcherIfConditionNode) ifConditionNode, "$cond1");

    // Follow the true edge. The next node should be another <div> open tag.
    HtmlMatcherGraphNode nextNode = ifConditionNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsOpenTagWithName(nextNode, "div");

    // The next node should be an accumulator node, which closes the true branch.
    HtmlMatcherGraphNode accNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(accNode).isInstanceOf(HtmlMatcherAccumulatorNode.class);

    // Follow the false edge from the {if $cond1} node. The next node should be the accumulator
    // node.
    nextNode = ifConditionNode.getNodeForEdgeKind(EdgeKind.FALSE_EDGE).get();
    assertThat(nextNode).isEqualTo(accNode);

    // Follow the graph through the accumulator node, this should be the {if cond2} node.
    ifConditionNode = accNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(ifConditionNode).isInstanceOf(HtmlMatcherIfConditionNode.class);
    assertThatIfExpressionEqualTo((HtmlMatcherIfConditionNode) ifConditionNode, "$cond2");

    // Follow a similar pattern through the true and false branches of this second if node.
    nextNode = ifConditionNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsOpenTagWithName(nextNode, "div");

    accNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(accNode).isInstanceOf(HtmlMatcherAccumulatorNode.class);

    nextNode = ifConditionNode.getNodeForEdgeKind(EdgeKind.FALSE_EDGE).get();
    assertThat(nextNode).isEqualTo(accNode);

    // The final two nodes should both be closing </div> tags.
    nextNode = accNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsCloseTagWithName(nextNode, "div");

    nextNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsCloseTagWithName(nextNode, "div");

    // Verify that the graph ends here.
    assertThat(nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE)).isAbsent();
  }

  @Test
  public void testIfElseifElseif() {
    // Arrange: set up the template under test.
    SoyFileNode template =
        parseTemplateBody(
            Joiner.on("\n")
                .join(
                    "{@param cond1: bool}",
                    "{@param cond2: bool}",
                    "{@param cond3: bool}",
                    "{if $cond1}<li>List 1",
                    "{elseif $cond2}<li>List 2",
                    "{elseif $cond3}<li>List 3",
                    "{/if}",
                    "</li>"));
    StrictHtmlValidationPassNewMatcher matcherPass =
        new StrictHtmlValidationPassNewMatcher(ErrorReporter.exploding());

    // Act: execute the graph builder.
    matcherPass.run(template, new IncrementingIdGenerator());
    Optional<HtmlMatcherGraph> matcherGraph = matcherPass.getHtmlMatcherGraph();

    // Assert: follow the graph and validate its structure.

    // The root node should be {if $cond1}.
    HtmlMatcherGraphNode ifConditionNode1 = matcherGraph.get().getRootNode().get();
    assertThat(ifConditionNode1).isInstanceOf(HtmlMatcherIfConditionNode.class);
    assertThatIfExpressionEqualTo((HtmlMatcherIfConditionNode) ifConditionNode1, "$cond1");

    // Follow the true branch.
    HtmlMatcherGraphNode nextNode = ifConditionNode1.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsOpenTagWithName(nextNode, "li");

    HtmlMatcherGraphNode accNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(accNode).isInstanceOf(HtmlMatcherAccumulatorNode.class);

    // Follow the false branch. This should lead to the {if $cond2} node.
    HtmlMatcherGraphNode ifConditionNode2 =
        ifConditionNode1.getNodeForEdgeKind(EdgeKind.FALSE_EDGE).get();
    assertThat(ifConditionNode1).isInstanceOf(HtmlMatcherIfConditionNode.class);
    assertThatIfExpressionEqualTo((HtmlMatcherIfConditionNode) ifConditionNode2, "$cond2");

    // Follow the true branch of {if $cond2}.
    nextNode = ifConditionNode2.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsOpenTagWithName(nextNode, "li");

    nextNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(nextNode).isEqualTo(accNode);

    // Follow the false branch. This should lead to the {if $cond3} node.
    HtmlMatcherGraphNode ifConditionNode3 =
        ifConditionNode2.getNodeForEdgeKind(EdgeKind.FALSE_EDGE).get();
    assertThat(ifConditionNode3).isInstanceOf(HtmlMatcherIfConditionNode.class);
    assertThatIfExpressionEqualTo((HtmlMatcherIfConditionNode) ifConditionNode3, "$cond3");

    // Follow the true branch of {if $cond3}.
    nextNode = ifConditionNode3.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsOpenTagWithName(nextNode, "li");

    nextNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    assertThat(nextNode).isEqualTo(accNode);

    // Follow the false branch. This should link to the accumulator node.
    nextNode = ifConditionNode3.getNodeForEdgeKind(EdgeKind.FALSE_EDGE).get();
    assertThat(nextNode).isEqualTo(accNode);

    // There should be a final closing </li> tag, then the graph ends.
    nextNode = nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE).get();
    TestUtils.assertNodeIsCloseTagWithName(nextNode, "li");

    // Verify that the graph ends here.
    assertThat(nextNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE)).isAbsent();
  }

  private void assertThatIfExpressionEqualTo(
      HtmlMatcherIfConditionNode ifConditionNode, String exprString) {
    assertThat(((IfCondNode) ifConditionNode.getSoyNode().get()).getExpr().toSourceString())
        .isEqualTo(exprString);
  }

  /**
   * Wraps the given template body with a {@code {template}} node and parses the result.
   *
   * <p><b>Note:</b> All parser passes up to {@code StrictHtmlValidationPass} are run. Tests in this
   * suite run the Strict HTML Validation pass manually.
   *
   * @return a Parse tree representing the given template body
   */
  private static SoyFileNode parseTemplateBody(String templateBody) {
    String soyFile =
        Joiner.on('\n').join("{namespace ns}", "", "{template .test}", templateBody, "{/template}");
    return SoyFileSetParserBuilder.forFileContents(soyFile)
        // Tests in this suite run the Strict HTML Validation passes manually.
        .addPassContinuationRule("StrictHtmlValidation", PassContinuationRule.STOP_BEFORE_PASS)
        .addPassContinuationRule(
            "StrictHtmlValidationPassNewMatcher", PassContinuationRule.STOP_BEFORE_PASS)
        // TODO(b/113531978): Remove the "new_html_matcher" flag when the new HTML matcher
        // goes live.
        .enableExperimentalFeatures(ImmutableList.of("new_html_matcher"))
        .desugarHtmlNodes(false)
        .errorReporter(ErrorReporter.createForTest())
        .parse()
        .fileSet()
        .getChild(0);
  }
}