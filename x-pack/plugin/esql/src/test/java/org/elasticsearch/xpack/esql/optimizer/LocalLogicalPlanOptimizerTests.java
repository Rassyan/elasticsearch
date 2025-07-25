/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.analysis.Analyzer;
import org.elasticsearch.xpack.esql.analysis.AnalyzerContext;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.AttributeSet;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.core.type.InvalidMappedField;
import org.elasticsearch.xpack.esql.core.util.Holder;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.EsqlFunctionRegistry;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.scalar.conditional.Case;
import org.elasticsearch.xpack.esql.expression.function.scalar.nulls.Coalesce;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.StartsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.RLike;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.RLikeList;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.WildcardLike;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.WildcardLikeList;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.nulls.IsNotNull;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.index.EsIndex;
import org.elasticsearch.xpack.esql.index.IndexResolution;
import org.elasticsearch.xpack.esql.optimizer.rules.logical.OptimizerRules;
import org.elasticsearch.xpack.esql.optimizer.rules.logical.local.InferIsNotNull;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.MvExpand;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.EsqlProject;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.rule.RuleExecutor;
import org.elasticsearch.xpack.esql.stats.SearchStats;
import org.hamcrest.Matchers;
import org.junit.BeforeClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.L;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.ONE;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.TEST_SEARCH_STATS;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.TEST_VERIFIER;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.THREE;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.TWO;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.as;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.asLimit;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.emptyInferenceResolution;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.emptyPolicyResolution;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.getFieldAttribute;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.greaterThanOf;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.loadMapping;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.statsForExistingField;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.statsForMissingField;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.unboundLogicalOptimizerContext;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.withDefaultLimitWarning;
import static org.elasticsearch.xpack.esql.core.tree.Source.EMPTY;
import static org.elasticsearch.xpack.esql.core.type.DataType.INTEGER;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.OptimizerRules.TransformDirection.DOWN;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.OptimizerRules.TransformDirection.UP;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

//@TestLogging(value = "org.elasticsearch.xpack.esql:TRACE", reason = "debug")
public class LocalLogicalPlanOptimizerTests extends ESTestCase {

    private static EsqlParser parser;
    private static Analyzer analyzer;
    private static LogicalPlanOptimizer logicalOptimizer;
    private static Map<String, EsField> mapping;

    @BeforeClass
    public static void init() {
        parser = new EsqlParser();

        mapping = loadMapping("mapping-basic.json");
        EsIndex test = new EsIndex("test", mapping, Map.of("test", IndexMode.STANDARD));
        IndexResolution getIndexResult = IndexResolution.valid(test);
        logicalOptimizer = new LogicalPlanOptimizer(unboundLogicalOptimizerContext());

        analyzer = new Analyzer(
            new AnalyzerContext(
                EsqlTestUtils.TEST_CFG,
                new EsqlFunctionRegistry(),
                getIndexResult,
                emptyPolicyResolution(),
                emptyInferenceResolution()
            ),
            TEST_VERIFIER
        );
    }

    /**
     * Expects
     * LocalRelation[[first_name{f}#4],EMPTY]
     */
    public void testMissingFieldInFilterNumeric() {
        var plan = plan("""
              from test
            | where emp_no > 10
            | keep first_name
            """);

        var testStats = statsForMissingField("emp_no");
        var localPlan = localPlan(plan, testStats);

        var empty = asEmptyRelation(localPlan);
        assertThat(Expressions.names(empty.output()), contains("first_name"));
    }

    /**
     * Expects
     * LocalRelation[[first_name{f}#4],EMPTY]
     */
    public void testMissingFieldInFilterString() {
        var plan = plan("""
              from test
            | where starts_with(last_name, "abc")
            | keep first_name
            """);

        var testStats = statsForMissingField("last_name");
        var localPlan = localPlan(plan, testStats);

        var empty = asEmptyRelation(localPlan);
        assertThat(Expressions.names(empty.output()), contains("first_name"));
    }

    /**
     * Expects
     * Project[[last_name{r}#7]]
     * \_Eval[[null[KEYWORD] AS last_name]]
     *   \_Limit[1000[INTEGER],false]
     *     \_EsRelation[test][_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gen..]
     */
    public void testMissingFieldInProject() {
        var plan = plan("""
              from test
            | keep last_name
            """);

        var testStats = statsForMissingField("last_name");
        var localPlan = localPlan(plan, testStats);

        var project = as(localPlan, Project.class);
        var projections = project.projections();
        assertThat(Expressions.names(projections), contains("last_name"));
        as(projections.get(0), ReferenceAttribute.class);
        var eval = as(project.child(), Eval.class);
        assertThat(Expressions.names(eval.fields()), contains("last_name"));
        var alias = as(eval.fields().get(0), Alias.class);
        var literal = as(alias.child(), Literal.class);
        assertThat(literal.value(), is(nullValue()));
        assertThat(literal.dataType(), is(DataType.KEYWORD));

        var limit = as(eval.child(), Limit.class);
        var source = as(limit.child(), EsRelation.class);
        assertThat(Expressions.names(source.output()), not(contains("last_name")));
    }

    /*
     * Expects a similar plan to testMissingFieldInProject() above, except for the Alias's child value
     * Project[[last_name{r}#4]]
     * \_Eval[[[66 6f 6f][KEYWORD] AS last_name]]
     *   \_Limit[1000[INTEGER],false]
     *     \_EsRelation[test][_meta_field{f}#11, emp_no{f}#5, first_name{f}#6, ge..]
     */
    public void testReassignedMissingFieldInProject() {
        var plan = plan("""
              from test
            | keep last_name
            | eval last_name = "foo"
            """);

        var testStats = statsForMissingField("last_name");
        var localPlan = localPlan(plan, testStats);

        var project = as(localPlan, Project.class);
        var projections = project.projections();
        assertThat(Expressions.names(projections), contains("last_name"));
        as(projections.get(0), ReferenceAttribute.class);
        var eval = as(project.child(), Eval.class);
        assertThat(Expressions.names(eval.fields()), contains("last_name"));
        var alias = as(eval.fields().get(0), Alias.class);
        var literal = as(alias.child(), Literal.class);
        assertThat(literal.value(), is(new BytesRef("foo")));
        assertThat(literal.dataType(), is(DataType.KEYWORD));

        var limit = as(eval.child(), Limit.class);
        var source = as(limit.child(), EsRelation.class);
        assertThat(Expressions.names(source.output()), not(contains("last_name")));
    }

    /**
     * Expects
     * EsqlProject[[first_name{f}#4]]
     * \_Limit[10000[INTEGER]]
     * \_EsRelation[test][_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, !ge..]
     */
    public void testMissingFieldInSort() {
        var plan = plan("""
              from test
            | sort last_name
            | keep first_name
            """);

        var testStats = statsForMissingField("last_name");
        var localPlan = localPlan(plan, testStats);

        var project = as(localPlan, Project.class);
        var projections = project.projections();
        assertThat(Expressions.names(projections), contains("first_name"));

        var limit = as(project.child(), Limit.class);
        var source = as(limit.child(), EsRelation.class);
        assertThat(Expressions.names(source.output()), not(contains("last_name")));
    }

    /**
     * Expects
     * EsqlProject[[first_name{f}#7, last_name{r}#17]]
     * \_Limit[1000[INTEGER],true]
     *   \_MvExpand[last_name{f}#10,last_name{r}#17]
     *     \_Project[[_meta_field{f}#12, emp_no{f}#6, first_name{f}#7, gender{f}#8, hire_date{f}#13, job{f}#14, job.raw{f}#15, lang
     * uages{f}#9, last_name{r}#10, long_noidx{f}#16, salary{f}#11]]
     *       \_Eval[[null[KEYWORD] AS last_name]]
     *         \_Limit[1000[INTEGER],false]
     *           \_EsRelation[test][_meta_field{f}#12, emp_no{f}#6, first_name{f}#7, ge..]
     */
    public void testMissingFieldInMvExpand() {
        var plan = plan("""
              from test
            | mv_expand last_name
            | keep first_name, last_name
            """);

        var testStats = statsForMissingField("last_name");
        var localPlan = localPlan(plan, testStats);

        // It'd be much better if this project was pushed down past the MvExpand, because MvExpand's cost scales with the number of
        // involved attributes/columns.
        var project = as(localPlan, EsqlProject.class);
        var projections = project.projections();
        assertThat(Expressions.names(projections), contains("first_name", "last_name"));

        var limit1 = asLimit(project.child(), 1000, true);
        var mvExpand = as(limit1.child(), MvExpand.class);
        var project2 = as(mvExpand.child(), Project.class);
        var eval = as(project2.child(), Eval.class);
        assertEquals(eval.fields().size(), 1);
        var lastName = eval.fields().get(0);
        assertEquals(lastName.name(), "last_name");
        assertEquals(lastName.child(), new Literal(EMPTY, null, DataType.KEYWORD));
        var limit2 = asLimit(eval.child(), 1000, false);
        var relation = as(limit2.child(), EsRelation.class);
        assertThat(Expressions.names(relation.output()), not(contains("last_name")));
    }

    public static class MockFieldAttributeCommand extends UnaryPlan {
        public FieldAttribute field;

        public MockFieldAttributeCommand(Source source, LogicalPlan child, FieldAttribute field) {
            super(source, child);
            this.field = field;
        }

        @Override
        protected AttributeSet computeReferences() {
            return AttributeSet.EMPTY;
        }

        public void writeTo(StreamOutput out) {
            throw new UnsupportedOperationException("not serialized");
        }

        @Override
        public String getWriteableName() {
            throw new UnsupportedOperationException("not serialized");
        }

        @Override
        public UnaryPlan replaceChild(LogicalPlan newChild) {
            return new MockFieldAttributeCommand(source(), newChild, field);
        }

        @Override
        public boolean expressionsResolved() {
            return true;
        }

        @Override
        public List<Attribute> output() {
            return List.of(field);
        }

        @Override
        protected NodeInfo<? extends LogicalPlan> info() {
            return NodeInfo.create(this, MockFieldAttributeCommand::new, child(), field);
        }
    }

    public void testMissingFieldInNewCommand() {
        var testStats = statsForMissingField("last_name");
        localPlan(
            new MockFieldAttributeCommand(
                EMPTY,
                new Row(EMPTY, List.of()),
                new FieldAttribute(EMPTY, "last_name", new EsField("last_name", DataType.KEYWORD, Map.of(), true))
            ),
            testStats
        );

        var plan = plan("""
              from test
            """);
        var initialRelation = plan.collectLeaves().get(0);
        FieldAttribute lastName = null;
        for (Attribute attr : initialRelation.output()) {
            if (attr.name().equals("last_name")) {
                lastName = (FieldAttribute) attr;
            }
        }

        // Expects
        // MockFieldAttributeCommand[last_name{f}#7]
        // \_Project[[_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gender{f}#5, hire_date{f}#10, job{f}#11, job.raw{f}#12, langu
        // ages{f}#6, last_name{r}#7, long_noidx{f}#13, salary{f}#8]]
        // \_Eval[[null[KEYWORD] AS last_name]]
        // \_Limit[1000[INTEGER],false]
        // \_EsRelation[test][_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gen..]
        LogicalPlan localPlan = localPlan(new MockFieldAttributeCommand(EMPTY, plan, lastName), testStats);

        var mockCommand = as(localPlan, MockFieldAttributeCommand.class);
        var project = as(mockCommand.child(), Project.class);
        var eval = as(project.child(), Eval.class);
        var limit = asLimit(eval.child(), 1000);
        var relation = as(limit.child(), EsRelation.class);

        assertThat(Expressions.names(eval.fields()), contains("last_name"));
        var literal = as(eval.fields().get(0), Alias.class);
        assertEquals(literal.child(), new Literal(EMPTY, null, DataType.KEYWORD));
        assertThat(Expressions.names(relation.output()), not(contains("last_name")));

        assertEquals(Expressions.names(initialRelation.output()), Expressions.names(project.output()));
    }

    /**
     * Expects
     * EsqlProject[[x{r}#3]]
     * \_Eval[[null[INTEGER] AS x]]
     *   \_Limit[10000[INTEGER]]
     *     \_EsRelation[test][_meta_field{f}#11, emp_no{f}#5, first_name{f}#6, !g..]
     */
    public void testMissingFieldInEval() {
        var plan = plan("""
              from test
            | eval x = emp_no + 1
            | keep x
            """);

        var testStats = statsForMissingField("emp_no");
        var localPlan = localPlan(plan, testStats);

        var project = as(localPlan, Project.class);
        assertThat(Expressions.names(project.projections()), contains("x"));
        var eval = as(project.child(), Eval.class);
        assertThat(Expressions.names(eval.fields()), contains("x"));

        var alias = as(eval.fields().get(0), Alias.class);
        var literal = as(alias.child(), Literal.class);
        assertThat(literal.value(), is(nullValue()));
        assertThat(literal.dataType(), is(DataType.INTEGER));

        var limit = as(eval.child(), Limit.class);
        var source = as(limit.child(), EsRelation.class);
    }

    /**
     * Expects
     * LocalRelation[[first_name{f}#4],EMPTY]
     */
    public void testMissingFieldInFilterNumericWithReference() {
        var plan = plan("""
              from test
            | eval x = emp_no
            | where x > 10
            | keep first_name
            """);

        var testStats = statsForMissingField("emp_no");
        var localPlan = localPlan(plan, testStats);

        var local = as(localPlan, LocalRelation.class);
        assertThat(Expressions.names(local.output()), contains("first_name"));
    }

    /**
     * Expects
     * LocalRelation[[first_name{f}#4],EMPTY]
     */
    public void testMissingFieldInFilterNumericWithReferenceToEval() {
        var plan = plan("""
              from test
            | eval x = emp_no + 1
            | where x > 10
            | keep first_name
            """);

        var testStats = statsForMissingField("emp_no");
        var localPlan = localPlan(plan, testStats);

        var local = as(localPlan, LocalRelation.class);
        assertThat(Expressions.names(local.output()), contains("first_name"));
    }

    /**
     * Expects
     * LocalRelation[[_meta_field{f}#11, emp_no{f}#5, first_name{f}#6, gender{f}#7, languages{f}#8, last_name{f}#9, salary{f}#10, x
     * {r}#3],EMPTY]
     */
    public void testMissingFieldInFilterNoProjection() {
        var plan = plan("""
              from test
            | eval x = emp_no
            | where x > 10
            """);

        var testStats = statsForMissingField("emp_no");
        var localPlan = localPlan(plan, testStats);

        var local = as(localPlan, LocalRelation.class);
        assertThat(
            Expressions.names(local.output()),
            contains(
                "_meta_field",
                "emp_no",
                "first_name",
                "gender",
                "hire_date",
                "job",
                "job.raw",
                "languages",
                "last_name",
                "long_noidx",
                "salary",
                "x"
            )
        );
    }

    public void testSparseDocument() throws Exception {
        var query = """
            from large
            | keep field00*
            | limit 10
            """;

        int size = 256;
        Map<String, EsField> large = Maps.newLinkedHashMapWithExpectedSize(size);
        for (int i = 0; i < size; i++) {
            var name = String.format(Locale.ROOT, "field%03d", i);
            large.put(name, new EsField(name, DataType.INTEGER, emptyMap(), true, false));
        }

        SearchStats searchStats = statsForExistingField("field000", "field001", "field002", "field003", "field004");

        EsIndex index = new EsIndex("large", large, Map.of("large", IndexMode.STANDARD));
        IndexResolution getIndexResult = IndexResolution.valid(index);
        var logicalOptimizer = new LogicalPlanOptimizer(unboundLogicalOptimizerContext());

        var analyzer = new Analyzer(
            new AnalyzerContext(
                EsqlTestUtils.TEST_CFG,
                new EsqlFunctionRegistry(),
                getIndexResult,
                emptyPolicyResolution(),
                emptyInferenceResolution()
            ),
            TEST_VERIFIER
        );

        var analyzed = analyzer.analyze(parser.createStatement(query, EsqlTestUtils.TEST_CFG));
        var optimized = logicalOptimizer.optimize(analyzed);
        var localContext = new LocalLogicalOptimizerContext(EsqlTestUtils.TEST_CFG, FoldContext.small(), searchStats);
        var plan = new LocalLogicalPlanOptimizer(localContext).localOptimize(optimized);

        var project = as(plan, Project.class);
        assertThat(project.projections(), hasSize(10));
        assertThat(
            Expressions.names(project.projections()),
            contains("field000", "field001", "field002", "field003", "field004", "field005", "field006", "field007", "field008", "field009")
        );
        var eval = as(project.child(), Eval.class);
        var field = eval.fields().get(0);
        assertThat(Expressions.name(field), is("field005"));
        assertThat(Alias.unwrap(field).fold(FoldContext.small()), Matchers.nullValue());
    }

    // InferIsNotNull

    public void testIsNotNullOnIsNullField() {
        EsRelation relation = relation();
        var fieldA = getFieldAttribute("a");
        Expression inn = isNotNull(fieldA);
        Filter f = new Filter(EMPTY, relation, inn);

        assertEquals(f, new InferIsNotNull().apply(f));
    }

    public void testIsNotNullOnOperatorWithOneField() {
        EsRelation relation = relation();
        var fieldA = getFieldAttribute("a");
        Expression inn = isNotNull(new Add(EMPTY, fieldA, ONE));
        Filter f = new Filter(EMPTY, relation, inn);
        Filter expected = new Filter(EMPTY, relation, new And(EMPTY, isNotNull(fieldA), inn));

        assertEquals(expected, new InferIsNotNull().apply(f));
    }

    public void testIsNotNullOnOperatorWithTwoFields() {
        EsRelation relation = relation();
        var fieldA = getFieldAttribute("a");
        var fieldB = getFieldAttribute("b");
        Expression inn = isNotNull(new Add(EMPTY, fieldA, fieldB));
        Filter f = new Filter(EMPTY, relation, inn);
        Filter expected = new Filter(EMPTY, relation, new And(EMPTY, new And(EMPTY, isNotNull(fieldA), isNotNull(fieldB)), inn));

        assertEquals(expected, new InferIsNotNull().apply(f));
    }

    public void testIsNotNullOnFunctionWithOneField() {
        EsRelation relation = relation();
        var fieldA = getFieldAttribute("a");
        var pattern = L("abc");
        Expression inn = isNotNull(new And(EMPTY, new StartsWith(EMPTY, fieldA, pattern), greaterThanOf(new Add(EMPTY, ONE, TWO), THREE)));

        Filter f = new Filter(EMPTY, relation, inn);
        Filter expected = new Filter(EMPTY, relation, new And(EMPTY, isNotNull(fieldA), inn));

        assertEquals(expected, new InferIsNotNull().apply(f));
    }

    public void testIsNotNullOnFunctionWithTwoFields() {
        EsRelation relation = relation();
        var fieldA = getFieldAttribute("a");
        var fieldB = getFieldAttribute("b");
        Expression inn = isNotNull(new StartsWith(EMPTY, fieldA, fieldB));

        Filter f = new Filter(EMPTY, relation, inn);
        Filter expected = new Filter(EMPTY, relation, new And(EMPTY, new And(EMPTY, isNotNull(fieldA), isNotNull(fieldB)), inn));

        assertEquals(expected, new InferIsNotNull().apply(f));
    }

    public void testIsNotNullOnCoalesce() {
        var plan = localPlan("""
              from test
            | where coalesce(emp_no, salary) is not null
            """);

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var inn = as(filter.condition(), IsNotNull.class);
        var coalesce = as(inn.children().get(0), Coalesce.class);
        assertThat(Expressions.names(coalesce.children()), contains("emp_no", "salary"));
        var source = as(filter.child(), EsRelation.class);
    }

    public void testIsNotNullOnExpression() {
        var plan = localPlan("""
              from test
            | eval x = emp_no + 1
            | where x is not null
            """);

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var inn = as(filter.condition(), IsNotNull.class);
        assertThat(Expressions.names(inn.children()), contains("x"));
        var eval = as(filter.child(), Eval.class);
        filter = as(eval.child(), Filter.class);
        inn = as(filter.condition(), IsNotNull.class);
        assertThat(Expressions.names(inn.children()), contains("emp_no"));
        var source = as(filter.child(), EsRelation.class);
    }

    public void testIsNotNullOnCase() {
        var plan = localPlan("""
              from test
            | where case(emp_no > 10000, "1", salary < 50000, "2", first_name) is not null
            """);

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var inn = as(filter.condition(), IsNotNull.class);
        var caseF = as(inn.children().get(0), Case.class);
        assertThat(Expressions.names(caseF.children()), contains("emp_no > 10000", "\"1\"", "salary < 50000", "\"2\"", "first_name"));
        var source = as(filter.child(), EsRelation.class);
    }

    public void testIsNotNullOnCase_With_IS_NULL() {
        var plan = localPlan("""
              from test
            | where case(emp_no IS NULL, "1", salary IS NOT NULL, "2", first_name) is not null
            """);

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var inn = as(filter.condition(), IsNotNull.class);
        var caseF = as(inn.children().get(0), Case.class);
        assertThat(Expressions.names(caseF.children()), contains("emp_no IS NULL", "\"1\"", "salary IS NOT NULL", "\"2\"", "first_name"));
        var source = as(filter.child(), EsRelation.class);
    }

    /*
     * Limit[1000[INTEGER],false]
     * \_Filter[RLIKE(first_name{f}#4, "VALÜ*", true)]
     *   \_EsRelation[test][_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gen..]
     */
    public void testReplaceUpperStringCasinqgWithInsensitiveRLike() {
        var plan = localPlan("FROM test | WHERE TO_UPPER(TO_LOWER(TO_UPPER(first_name))) RLIKE \"VALÜ*\"");

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var rlike = as(filter.condition(), RLike.class);
        var field = as(rlike.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertThat(rlike.pattern().pattern(), is("VALÜ*"));
        assertThat(rlike.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    /*
     *Limit[1000[INTEGER],false]
     * \_Filter[RLikeList(first_name{f}#4, "("VALÜ*", "TEST*")", true)]
     *  \_EsRelation[test][_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gen..]
     */
    public void testReplaceUpperStringCasinqWithInsensitiveRLikeList() {
        var plan = localPlan("FROM test | WHERE TO_UPPER(TO_LOWER(TO_UPPER(first_name))) RLIKE (\"VALÜ*\", \"TEST*\")");

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var rLikeList = as(filter.condition(), RLikeList.class);
        var field = as(rLikeList.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertEquals(2, rLikeList.pattern().patternList().size());
        assertThat(rLikeList.pattern().patternList().get(0).pattern(), is("VALÜ*"));
        assertThat(rLikeList.pattern().patternList().get(1).pattern(), is("TEST*"));
        assertThat(rLikeList.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    // same plan as above, but lower case pattern
    public void testReplaceLowerStringCasingWithInsensitiveRLike() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) RLIKE \"valü*\"");

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var rlike = as(filter.condition(), RLike.class);
        var field = as(rlike.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertThat(rlike.pattern().pattern(), is("valü*"));
        assertThat(rlike.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    // same plan as above, but lower case pattern and list of patterns
    public void testReplaceLowerStringCasingWithInsensitiveRLikeList() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) RLIKE (\"valü*\", \"test*\")");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var rLikeList = as(filter.condition(), RLikeList.class);
        var field = as(rLikeList.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertEquals(2, rLikeList.pattern().patternList().size());
        assertThat(rLikeList.pattern().patternList().get(0).pattern(), is("valü*"));
        assertThat(rLikeList.pattern().patternList().get(1).pattern(), is("test*"));
        assertThat(rLikeList.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    // same plan as above, but lower case pattern and list of patterns, one of which is upper case
    public void testReplaceLowerStringCasingWithMixedCaseRLikeList() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) RLIKE (\"valü*\", \"TEST*\")");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var rLikeList = as(filter.condition(), RLikeList.class);
        var field = as(rLikeList.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertEquals(1, rLikeList.pattern().patternList().size());
        assertThat(rLikeList.pattern().patternList().get(0).pattern(), is("valü*"));
        assertThat(rLikeList.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    /**
     * LocalRelation[[_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gender{f}#5, hire_date{f}#10, job{f}#11, job.raw{f}#12, langu
     *   ages{f}#6, last_name{f}#7, long_noidx{f}#13, salary{f}#8],EMPTY]
     */
    public void testReplaceStringCasingAndRLikeWithLocalRelation() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) RLIKE \"VALÜ*\"");

        var local = as(plan, LocalRelation.class);
        assertThat(local.supplier(), equalTo(EmptyLocalSupplier.EMPTY));
    }

    // same plan as in testReplaceUpperStringCasingWithInsensitiveRLike, but with LIKE instead of RLIKE
    public void testReplaceUpperStringCasingWithInsensitiveLike() {
        var plan = localPlan("FROM test | WHERE TO_UPPER(TO_LOWER(TO_UPPER(first_name))) LIKE \"VALÜ*\"");

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var wlike = as(filter.condition(), WildcardLike.class);
        var field = as(wlike.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertThat(wlike.pattern().pattern(), is("VALÜ*"));
        assertThat(wlike.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    // same plan as in testReplaceUpperStringCasingWithInsensitiveRLikeList, but with LIKE instead of RLIKE
    public void testReplaceUpperStringCasingWithInsensitiveLikeList() {
        var plan = localPlan("FROM test | WHERE TO_UPPER(TO_LOWER(TO_UPPER(first_name))) LIKE (\"VALÜ*\", \"TEST*\")");

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var likeList = as(filter.condition(), WildcardLikeList.class);
        var field = as(likeList.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertEquals(2, likeList.pattern().patternList().size());
        assertThat(likeList.pattern().patternList().get(0).pattern(), is("VALÜ*"));
        assertThat(likeList.pattern().patternList().get(1).pattern(), is("TEST*"));
        assertThat(likeList.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    // same plan as above, but mixed case pattern and list of patterns
    public void testReplaceLowerStringCasingWithMixedCaseLikeList() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) LIKE (\"TEST*\", \"valü*\", \"vaLü*\")");
        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var likeList = as(filter.condition(), WildcardLikeList.class);
        var field = as(likeList.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        // only the all lowercase pattern is kept, the mixed case and all uppercase patterns are ignored
        assertEquals(1, likeList.pattern().patternList().size());
        assertThat(likeList.pattern().patternList().get(0).pattern(), is("valü*"));
        assertThat(likeList.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    // same plan as above, but lower case pattern
    public void testReplaceLowerStringCasingWithInsensitiveLike() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) LIKE \"valü*\"");

        var limit = as(plan, Limit.class);
        var filter = as(limit.child(), Filter.class);
        var wlike = as(filter.condition(), WildcardLike.class);
        var field = as(wlike.field(), FieldAttribute.class);
        assertThat(field.fieldName().string(), is("first_name"));
        assertThat(wlike.pattern().pattern(), is("valü*"));
        assertThat(wlike.caseInsensitive(), is(true));
        var source = as(filter.child(), EsRelation.class);
    }

    /**
     * LocalRelation[[_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gender{f}#5, hire_date{f}#10, job{f}#11, job.raw{f}#12, langu
     *   ages{f}#6, last_name{f}#7, long_noidx{f}#13, salary{f}#8],EMPTY]
     */
    public void testReplaceStringCasingAndLikeWithLocalRelation() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) LIKE \"VALÜ*\"");

        var local = as(plan, LocalRelation.class);
        assertThat(local.supplier(), equalTo(EmptyLocalSupplier.EMPTY));
    }

    /**
     * LocalRelation[[_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gender{f}#5, hire_date{f}#10, job{f}#11, job.raw{f}#12, langu
     *   ages{f}#6, last_name{f}#7, long_noidx{f}#13, salary{f}#8],EMPTY]
     */
    public void testReplaceStringCasingAndLikeListWithLocalRelation() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) LIKE (\"VALÜ*\", \"TEST*\")");

        var local = as(plan, LocalRelation.class);
        assertThat(local.supplier(), equalTo(EmptyLocalSupplier.EMPTY));
    }

    /**
     * LocalRelation[[_meta_field{f}#9, emp_no{f}#3, first_name{f}#4, gender{f}#5, hire_date{f}#10, job{f}#11, job.raw{f}#12, langu
     *   ages{f}#6, last_name{f}#7, long_noidx{f}#13, salary{f}#8],EMPTY]
     */
    public void testReplaceStringCasingAndRLikeListWithLocalRelation() {
        var plan = localPlan("FROM test | WHERE TO_LOWER(TO_UPPER(first_name)) RLIKE (\"VALÜ*\", \"TEST*\")");

        var local = as(plan, LocalRelation.class);
        assertThat(local.supplier(), equalTo(EmptyLocalSupplier.EMPTY));
    }

    /**
     * Limit[1000[INTEGER],false]
     * \_Aggregate[[],[SUM($$integer_long_field$converted_to$long{f$}#5,true[BOOLEAN]) AS sum(integer_long_field::long)#3]]
     *   \_Filter[ISNOTNULL($$integer_long_field$converted_to$long{f$}#5)]
     *     \_EsRelation[test*][!integer_long_field, $$integer_long_field$converted..]
     */
    public void testUnionTypesInferNonNullAggConstraint() {
        LogicalPlan coordinatorOptimized = plan("FROM test* | STATS sum(integer_long_field::long)", analyzerWithUnionTypeMapping());
        var plan = localPlan(coordinatorOptimized, TEST_SEARCH_STATS);

        var limit = asLimit(plan, 1000);
        var agg = as(limit.child(), Aggregate.class);
        var filter = as(agg.child(), Filter.class);
        var relation = as(filter.child(), EsRelation.class);

        var isNotNull = as(filter.condition(), IsNotNull.class);
        var unionTypeField = as(isNotNull.field(), FieldAttribute.class);
        assertEquals("$$integer_long_field$converted_to$long", unionTypeField.name());
        assertEquals("integer_long_field", unionTypeField.fieldName().string());
    }

    /**
     * \_Aggregate[[first_name{r}#7, $$first_name$temp_name$17{r}#18],[SUM(salary{f}#11,true[BOOLEAN]) AS SUM(salary)#5, first_nam
     * e{r}#7, first_name{r}#7 AS last_name#10]]
     *   \_Eval[[null[KEYWORD] AS first_name#7, null[KEYWORD] AS $$first_name$temp_name$17#18]]
     *     \_EsRelation[test][_meta_field{f}#12, emp_no{f}#6, first_name{f}#7, ge..]
     */
    public void testGroupingByMissingFields() {
        var plan = plan("FROM test | STATS SUM(salary) BY first_name, last_name");
        var testStats = statsForMissingField("first_name", "last_name");
        var localPlan = localPlan(plan, testStats);
        Limit limit = as(localPlan, Limit.class);
        Aggregate aggregate = as(limit.child(), Aggregate.class);
        assertThat(aggregate.groupings(), hasSize(2));
        ReferenceAttribute grouping1 = as(aggregate.groupings().get(0), ReferenceAttribute.class);
        ReferenceAttribute grouping2 = as(aggregate.groupings().get(1), ReferenceAttribute.class);
        Eval eval = as(aggregate.child(), Eval.class);
        assertThat(eval.fields(), hasSize(2));
        Alias eval1 = eval.fields().get(0);
        Literal literal1 = as(eval1.child(), Literal.class);
        assertNull(literal1.value());
        assertThat(literal1.dataType(), is(DataType.KEYWORD));
        Alias eval2 = eval.fields().get(1);
        Literal literal2 = as(eval2.child(), Literal.class);
        assertNull(literal2.value());
        assertThat(literal2.dataType(), is(DataType.KEYWORD));
        assertThat(grouping1.id(), equalTo(eval1.id()));
        assertThat(grouping2.id(), equalTo(eval2.id()));
        as(eval.child(), EsRelation.class);
    }

    public void testVerifierOnMissingReferences() throws Exception {
        var plan = localPlan("""
            from test
            | stats a = min(salary) by emp_no
            """);

        var limit = as(plan, Limit.class);
        var aggregate = as(limit.child(), Aggregate.class);
        var min = as(Alias.unwrap(aggregate.aggregates().get(0)), Min.class);
        var salary = as(min.field(), NamedExpression.class);
        assertThat(salary.name(), is("salary"));
        // emulate a rule that adds an invalid field
        var invalidPlan = new OrderBy(
            limit.source(),
            limit,
            asList(new Order(limit.source(), salary, Order.OrderDirection.ASC, Order.NullsPosition.FIRST))
        );

        var localContext = new LocalLogicalOptimizerContext(EsqlTestUtils.TEST_CFG, FoldContext.small(), TEST_SEARCH_STATS);
        LocalLogicalPlanOptimizer localLogicalPlanOptimizer = new LocalLogicalPlanOptimizer(localContext);

        IllegalStateException e = expectThrows(IllegalStateException.class, () -> localLogicalPlanOptimizer.localOptimize(invalidPlan));
        assertThat(e.getMessage(), containsString("Plan [OrderBy[[Order[salary"));
        assertThat(e.getMessage(), containsString(" optimized incorrectly due to missing references [salary"));
    }

    private LocalLogicalPlanOptimizer getCustomRulesLocalLogicalPlanOptimizer(List<RuleExecutor.Batch<LogicalPlan>> batches) {
        LocalLogicalOptimizerContext context = new LocalLogicalOptimizerContext(
            EsqlTestUtils.TEST_CFG,
            FoldContext.small(),
            TEST_SEARCH_STATS
        );
        LocalLogicalPlanOptimizer customOptimizer = new LocalLogicalPlanOptimizer(context) {
            @Override
            protected List<Batch<LogicalPlan>> batches() {
                return batches;
            }
        };
        return customOptimizer;
    }

    public void testVerifierOnAdditionalAttributeAdded() throws Exception {
        var plan = localPlan("""
            from test
            | stats a = min(salary) by emp_no
            """);

        var limit = as(plan, Limit.class);
        var aggregate = as(limit.child(), Aggregate.class);
        var min = as(Alias.unwrap(aggregate.aggregates().get(0)), Min.class);
        var salary = as(min.field(), NamedExpression.class);
        assertThat(salary.name(), is("salary"));
        Holder<Integer> appliedCount = new Holder<>(0);
        // use a custom rule that adds another output attribute
        var customRuleBatch = new RuleExecutor.Batch<>(
            "CustomRuleBatch",
            RuleExecutor.Limiter.ONCE,
            new OptimizerRules.ParameterizedOptimizerRule<Aggregate, LocalLogicalOptimizerContext>(UP) {

                @Override
                protected LogicalPlan rule(Aggregate plan, LocalLogicalOptimizerContext context) {
                    // This rule adds a missing attribute to the plan output
                    // We only want to apply it once, so we use a static counter
                    if (appliedCount.get() == 0) {
                        appliedCount.set(appliedCount.get() + 1);
                        Literal additionalLiteral = new Literal(Source.EMPTY, "additional literal", INTEGER);
                        return new Eval(plan.source(), plan, List.of(new Alias(Source.EMPTY, "additionalAttribute", additionalLiteral)));
                    }
                    return plan;
                }

            }
        );
        LocalLogicalPlanOptimizer customRulesLocalLogicalPlanOptimizer = getCustomRulesLocalLogicalPlanOptimizer(List.of(customRuleBatch));
        Exception e = expectThrows(VerificationException.class, () -> customRulesLocalLogicalPlanOptimizer.localOptimize(plan));
        assertThat(e.getMessage(), containsString("Output has changed from"));
        assertThat(e.getMessage(), containsString("additionalAttribute"));
    }

    public void testVerifierOnAttributeDatatypeChanged() {
        var plan = localPlan("""
            from test
            | stats a = min(salary) by emp_no
            """);

        var limit = as(plan, Limit.class);
        var aggregate = as(limit.child(), Aggregate.class);
        var min = as(Alias.unwrap(aggregate.aggregates().get(0)), Min.class);
        var salary = as(min.field(), NamedExpression.class);
        assertThat(salary.name(), is("salary"));
        Holder<Integer> appliedCount = new Holder<>(0);
        // use a custom rule that changes the datatype of an output attribute
        var customRuleBatch = new RuleExecutor.Batch<>(
            "CustomRuleBatch",
            RuleExecutor.Limiter.ONCE,
            new OptimizerRules.ParameterizedOptimizerRule<LogicalPlan, LocalLogicalOptimizerContext>(DOWN) {
                @Override
                protected LogicalPlan rule(LogicalPlan plan, LocalLogicalOptimizerContext context) {
                    // We only want to apply it once, so we use a static counter
                    if (appliedCount.get() == 0) {
                        appliedCount.set(appliedCount.get() + 1);
                        Limit limit = as(plan, Limit.class);
                        Limit newLimit = new Limit(plan.source(), limit.limit(), limit.child()) {
                            @Override
                            public List<Attribute> output() {
                                List<Attribute> oldOutput = super.output();
                                List<Attribute> newOutput = new ArrayList<>(oldOutput);
                                newOutput.set(0, oldOutput.get(0).withDataType(DataType.DATETIME));
                                return newOutput;
                            }
                        };
                        return newLimit;
                    }
                    return plan;
                }

            }
        );
        LocalLogicalPlanOptimizer customRulesLocalLogicalPlanOptimizer = getCustomRulesLocalLogicalPlanOptimizer(List.of(customRuleBatch));
        Exception e = expectThrows(VerificationException.class, () -> customRulesLocalLogicalPlanOptimizer.localOptimize(plan));
        assertThat(e.getMessage(), containsString("Output has changed from"));
    }

    private IsNotNull isNotNull(Expression field) {
        return new IsNotNull(EMPTY, field);
    }

    private LocalRelation asEmptyRelation(Object o) {
        var empty = as(o, LocalRelation.class);
        assertThat(empty.supplier(), is(EmptyLocalSupplier.EMPTY));
        return empty;
    }

    private LogicalPlan plan(String query, Analyzer analyzer) {
        var analyzed = analyzer.analyze(parser.createStatement(query, EsqlTestUtils.TEST_CFG));
        return logicalOptimizer.optimize(analyzed);
    }

    protected LogicalPlan plan(String query) {
        return plan(query, analyzer);
    }

    protected LogicalPlan localPlan(LogicalPlan plan, SearchStats searchStats) {
        var localContext = new LocalLogicalOptimizerContext(EsqlTestUtils.TEST_CFG, FoldContext.small(), searchStats);
        return new LocalLogicalPlanOptimizer(localContext).localOptimize(plan);
    }

    private LogicalPlan localPlan(String query) {
        return localPlan(plan(query), TEST_SEARCH_STATS);
    }

    private static Analyzer analyzerWithUnionTypeMapping() {
        InvalidMappedField unionTypeField = new InvalidMappedField(
            "integer_long_field",
            Map.of("integer", Set.of("test1"), "long", Set.of("test2"))
        );

        EsIndex test = new EsIndex(
            "test*",
            Map.of("integer_long_field", unionTypeField),
            Map.of("test1", IndexMode.STANDARD, "test2", IndexMode.STANDARD)
        );
        IndexResolution getIndexResult = IndexResolution.valid(test);

        return new Analyzer(
            new AnalyzerContext(
                EsqlTestUtils.TEST_CFG,
                new EsqlFunctionRegistry(),
                getIndexResult,
                emptyPolicyResolution(),
                emptyInferenceResolution()
            ),
            TEST_VERIFIER
        );
    }

    @Override
    protected List<String> filteredWarnings() {
        return withDefaultLimitWarning(super.filteredWarnings());
    }

    public static EsRelation relation() {
        return new EsRelation(EMPTY, new EsIndex(randomAlphaOfLength(8), emptyMap()), randomFrom(IndexMode.values()));
    }
}
