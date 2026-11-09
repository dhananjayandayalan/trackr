package dev.trakr.api.search.internal.tql;

import dev.trakr.api.search.internal.tql.ast.And;
import dev.trakr.api.search.internal.tql.ast.Compare;
import dev.trakr.api.search.internal.tql.ast.Expr;
import dev.trakr.api.search.internal.tql.ast.Field;
import dev.trakr.api.search.internal.tql.ast.In;
import dev.trakr.api.search.internal.tql.ast.IsEmpty;
import dev.trakr.api.search.internal.tql.ast.Not;
import dev.trakr.api.search.internal.tql.ast.Op;
import dev.trakr.api.search.internal.tql.ast.Or;
import dev.trakr.api.search.internal.tql.ast.OrderBy;
import dev.trakr.api.search.internal.tql.ast.TextMatch;
import dev.trakr.api.search.internal.tql.ast.TqlQuery;
import dev.trakr.api.search.internal.tql.ast.Value;
import dev.trakr.api.search.internal.tql.grammar.TqlBaseVisitor;
import dev.trakr.api.search.internal.tql.grammar.TqlLexer;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.AndExprContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.CompareClauseContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.DateValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.EmptyClauseContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.FieldContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.FunctionContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.FunctionValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.IdentValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.InClauseContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.IssueKeyValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.NegatedContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.NumberValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.OpContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.OrExprContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.ParenthesizedContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.PlainContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.QueryContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.RelativeDateValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.SingleContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.SortKeyContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.StringValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.TextClauseContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.ValueContext;
import dev.trakr.api.search.internal.tql.grammar.TqlParser.ValueListContext;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Parse tree → AST, with the visitor pattern. The parse tree mirrors the grammar rule for rule (an
 * orExpr holding one andExpr holding one notExpr… for a single clause); the AST keeps only what the query
 * means. ANTLR generates TqlBaseVisitor with one {@code visitX} method per rule and per {@code # label};
 * each override here builds the record for its piece and calls {@code visit(child)} for the pieces inside,
 * so the recursion follows the tree.
 *
 * <p>The visitor returns {@link Expr}; values and sort keys aren't expressions, so plain helper methods
 * build those.
 */
final class AstBuilder extends TqlBaseVisitor<Expr> {

    TqlQuery build(QueryContext query) {
        Expr where = query.orExpr() == null ? null : visit(query.orExpr());
        List<OrderBy> orderBy = query.orderBy() == null ? List.of()
                : query.orderBy().sortKey().stream().map(AstBuilder::sortKey).toList();
        return new TqlQuery(where, orderBy);
    }

    // BLOCK 1: boolean structure — a OR b OR c becomes ((a OR b) OR c); the compiler flattens it again
    @Override
    public Expr visitOrExpr(OrExprContext ctx) {
        Expr result = visit(ctx.andExpr(0));
        for (int i = 1; i < ctx.andExpr().size(); i++) {
            result = new Or(result, visit(ctx.andExpr(i)));
        }
        return result;
    }

    @Override
    public Expr visitAndExpr(AndExprContext ctx) {
        Expr result = visit(ctx.notExpr(0));
        for (int i = 1; i < ctx.notExpr().size(); i++) {
            result = new And(result, visit(ctx.notExpr(i)));
        }
        return result;
    }

    @Override
    public Expr visitNegated(NegatedContext ctx) {
        return new Not(visit(ctx.notExpr()));
    }

    // the layers that only pass a single child through disappear from the AST
    @Override
    public Expr visitPlain(PlainContext ctx) {
        return visit(ctx.atom());
    }

    @Override
    public Expr visitParenthesized(ParenthesizedContext ctx) {
        return visit(ctx.orExpr());
    }

    @Override
    public Expr visitSingle(SingleContext ctx) {
        return visit(ctx.clause());
    }

    // BLOCK 2: clauses, one method per # label in the grammar
    @Override
    public Expr visitCompareClause(CompareClauseContext ctx) {
        return new Compare(field(ctx.field()), op(ctx.op()), value(ctx.value()));
    }

    @Override
    public Expr visitInClause(InClauseContext ctx) {
        ValueListContext list = ctx.valueList();
        List<Value> values = list.function() != null ? List.of(function(list.function()))
                : list.value().stream().map(AstBuilder::value).toList();
        return new In(field(ctx.field()), ctx.NOT() != null, values);
    }

    @Override
    public Expr visitEmptyClause(EmptyClauseContext ctx) {
        return new IsEmpty(field(ctx.field()), ctx.NOT() != null);
    }

    @Override
    public Expr visitTextClause(TextClauseContext ctx) {
        String text = ctx.STRING() != null ? unquote(ctx.STRING().getText()) : ctx.IDENT().getText();
        return new TextMatch(field(ctx.field()), text);
    }

    // BLOCK 3: values, sort keys, operators
    private static Value value(ValueContext ctx) {
        int at = position(ctx);
        return switch (ctx) {
            case StringValueContext s -> new Value.Str(unquote(s.STRING().getText()), at);
            case NumberValueContext n -> new Value.Num(n.getText(), at);
            case RelativeDateValueContext r -> new Value.RelDate(r.getText(), at);
            case DateValueContext d -> new Value.DateValue(d.getText(), at);
            case IssueKeyValueContext k -> new Value.IssueKey(k.getText(), at);
            case IdentValueContext i -> new Value.Ident(i.getText(), at);
            case FunctionValueContext f -> function(f.function());
            // the generated context classes aren't sealed, so the compiler can't know the list is complete
            default -> throw new IllegalStateException("unknown value alternative " + ctx.getClass().getSimpleName());
        };
    }

    private static Value function(FunctionContext ctx) {
        return new Value.FunctionCall(ctx.IDENT().getText(), ctx.value().stream().map(AstBuilder::value).toList(),
                position(ctx));
    }

    private static OrderBy sortKey(SortKeyContext ctx) {
        return new OrderBy(field(ctx.field()), ctx.DESC() != null);
    }

    private static Field field(FieldContext ctx) {
        return new Field(ctx.getText(), position(ctx));
    }

    private static Op op(OpContext ctx) {
        return switch (ctx.getStart().getType()) {
            case TqlLexer.EQ -> Op.EQ;
            case TqlLexer.NE -> Op.NE;
            case TqlLexer.GT -> Op.GT;
            case TqlLexer.GE -> Op.GE;
            case TqlLexer.LT -> Op.LT;
            case TqlLexer.LE -> Op.LE;
            default -> throw new IllegalStateException("unknown operator " + ctx.getText());
        };
    }

    private static int position(ParserRuleContext ctx) {
        return ctx.getStart().getStartIndex();
    }

    /** {@code "say \"hi\""} → {@code say "hi"}: drops the outer quotes, resolves \" and \\. */
    static String unquote(String quoted) {
        StringBuilder text = new StringBuilder(quoted.length());
        for (int i = 1; i < quoted.length() - 1; i++) {
            char c = quoted.charAt(i);
            if (c == '\\' && i + 1 < quoted.length() - 1) {
                c = quoted.charAt(++i);
            }
            text.append(c);
        }
        return text.toString();
    }
}
