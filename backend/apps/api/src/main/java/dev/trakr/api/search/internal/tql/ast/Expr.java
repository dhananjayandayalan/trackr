package dev.trakr.api.search.internal.tql.ast;

/**
 * A TQL condition, as a tree. Sealed: these seven records are the only kinds there are, so a
 * {@code switch (expr)} over them is exhaustive and needs no {@code default} — add an eighth and every
 * switch that forgot it stops compiling.
 *
 * <p>{@code toString()} prints the tree with explicit parentheses, e.g. {@code (a OR (b AND c))}: the
 * parser tests compare those strings, so precedence is visible at a glance.
 */
public sealed interface Expr permits And, Or, Not, Compare, In, IsEmpty, TextMatch {
}
