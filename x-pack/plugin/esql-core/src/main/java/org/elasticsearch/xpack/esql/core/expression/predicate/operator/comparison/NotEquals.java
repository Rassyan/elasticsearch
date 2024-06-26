/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.core.expression.predicate.operator.comparison;

import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.predicate.Negatable;
import org.elasticsearch.xpack.esql.core.expression.predicate.operator.comparison.BinaryComparisonProcessor.BinaryComparisonOperation;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.time.ZoneId;

public class NotEquals extends BinaryComparison implements Negatable<BinaryComparison> {

    public NotEquals(Source source, Expression left, Expression right, ZoneId zoneId) {
        super(source, left, right, BinaryComparisonOperation.NEQ, zoneId);
    }

    @Override
    protected NodeInfo<NotEquals> info() {
        return NodeInfo.create(this, NotEquals::new, left(), right(), zoneId());
    }

    @Override
    protected NotEquals replaceChildren(Expression newLeft, Expression newRight) {
        return new NotEquals(source(), newLeft, newRight, zoneId());
    }

    @Override
    public NotEquals swapLeftAndRight() {
        return new NotEquals(source(), right(), left(), zoneId());
    }

    @Override
    public BinaryComparison negate() {
        return new Equals(source(), left(), right(), zoneId());
    }

    @Override
    public BinaryComparison reverse() {
        return this;
    }

    @Override
    protected boolean isCommutative() {
        return true;
    }
}
