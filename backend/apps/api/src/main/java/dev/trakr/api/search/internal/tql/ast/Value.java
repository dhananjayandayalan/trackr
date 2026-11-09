package dev.trakr.api.search.internal.tql.ast;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A value as the user wrote it — one record per token kind, still unresolved: {@code Ada} is just a
 * word here; ValueResolver later decides it means a user id because it sits next to {@code assignee}.
 * {@code position} is where it starts in the query, for error messages.
 */
public sealed interface Value {

    int position();

    /** The value's own text, without quotes: what a label or a name lookup compares against. */
    String text();

    /** A bare word: {@code Done}, {@code MAR}, {@code true}. */
    record Ident(String text, int position) implements Value {

        @Override
        public String toString() {
            return text;
        }
    }

    /** A double-quoted string, escapes already removed: {@code "In review"}. */
    record Str(String text, int position) implements Value {

        @Override
        public String toString() {
            return quote(text);
        }
    }

    /** {@code 3}, {@code 0.5}, {@code -2}. */
    record Num(String text, int position) implements Value {

        @Override
        public String toString() {
            return text;
        }
    }

    /** {@code -7d}, {@code 2w}, {@code 12h}, {@code 30m}: relative to now. */
    record RelDate(String text, int position) implements Value {

        @Override
        public String toString() {
            return text;
        }
    }

    /** {@code 2026-11-09}. */
    record DateValue(String text, int position) implements Value {

        @Override
        public String toString() {
            return text;
        }
    }

    /** {@code MAR-131}, uppercased (the lexer is case-insensitive, keys are not). */
    record IssueKey(String text, int position) implements Value {

        public IssueKey {
            text = text.toUpperCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** {@code currentUser()}, {@code membersOf("MAR")}. */
    record FunctionCall(String name, List<Value> args, int position) implements Value {

        public FunctionCall {
            args = List.copyOf(args);
        }

        @Override
        public String text() {
            return name;
        }

        @Override
        public String toString() {
            return name + "(" + args.stream().map(Value::toString).collect(Collectors.joining(", ")) + ")";
        }
    }

    /** Back to TQL syntax: {@code "say \"hi\""}. */
    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
