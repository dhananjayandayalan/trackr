package dev.trakr.api.search.internal.tql.ast;

/** The comparison operators of {@code field op value}. */
public enum Op {
    EQ("="),
    NE("!="),
    GT(">"),
    GE(">="),
    LT("<"),
    LE("<=");

    private final String symbol;

    Op(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public boolean isRange() {
        return this == GT || this == GE || this == LT || this == LE;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
