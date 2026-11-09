// TQL, the TRAKR Query Language (05-search §3). ANTLR generates TqlLexer, TqlParser and TqlBaseVisitor
// from this file at build time (Gradle's generateGrammarSource, before compileJava).
//
// A combined grammar: parser rules start lowercase, lexer rules uppercase.

// BLOCK 1: grammar header + options
grammar Tql;

// 'and', 'AND' and 'And' are the same keyword, [a-z] also matches A-Z (ANTLR 4.10+). Token text keeps its
// original case, so "Ada Lovelace" and MAR stay as typed.
options { caseInsensitive = true; }

// BLOCK 2: entry rule — an empty query is valid (it means "everything I can see"); EOF makes the parser
// reject trailing garbage instead of silently stopping after the last complete clause.
query
    : orExpr? orderBy? EOF
    ;

// BLOCK 3: boolean precedence. Each layer is built from the next, tighter one: an orExpr is a list of
// andExprs, an andExpr a list of notExprs. So in `a OR b AND c` the parser must finish `b AND c` (one
// andExpr) before it can see the OR again: AND binds tighter than OR, NOT tighter than both, and
// parentheses (back at the top, orExpr) override all of it. No precedence table needed.
orExpr
    : andExpr (OR andExpr)*
    ;

andExpr
    : notExpr (AND notExpr)*
    ;

notExpr
    : NOT notExpr # negated
    | atom        # plain
    ;

atom
    : LPAREN orExpr RPAREN # parenthesized
    | clause               # single
    ;

// BLOCK 4: clauses — each alternative has a # label, so the visitor gets one method per alternative
// (visitCompareClause, visitInClause, …) instead of one method that has to work out which it got.
clause
    : field op value               # compareClause
    | field NOT? IN valueList      # inClause
    | field IS NOT? EMPTY          # emptyClause
    | field TILDE (STRING | IDENT) # textClause
    ;

// a list in parentheses, or a function that stands for one: sprint in openSprints()
valueList
    : LPAREN value (COMMA value)* RPAREN
    | function
    ;

// any name; FieldRegistry decides afterwards which names are fields, so a typo gets "unknown field
// 'stauts'" instead of a vague syntax error
field
    : IDENT
    ;

op
    : EQ | NE | GT | GE | LT | LE
    ;

// BLOCK 5: values and functions
value
    : STRING   # stringValue
    | NUMBER   # numberValue
    | RELDATE  # relativeDateValue
    | DATE     # dateValue
    | ISSUEKEY # issueKeyValue
    | function # functionValue
    | IDENT    # identValue
    ;

function
    : IDENT LPAREN (value (COMMA value)*)? RPAREN
    ;

// BLOCK 6: order by
orderBy
    : ORDER BY sortKey (COMMA sortKey)*
    ;

sortKey
    : field (ASC | DESC)?
    ;

// BLOCK 7: lexer rules. The lexer takes the LONGEST match; on a tie, the rule written FIRST wins. So the
// keywords come before IDENT ('and' is 3 characters for both AND and IDENT: AND wins), and MAR-131 is
// one ISSUEKEY (7 characters) rather than IDENT MAR (3) followed by more tokens.
AND   : 'and' ;
OR    : 'or' ;
NOT   : 'not' ;
IN    : 'in' ;
IS    : 'is' ;
EMPTY : 'empty' ;
ORDER : 'order' ;
BY    : 'by' ;
ASC   : 'asc' ;
DESC  : 'desc' ;

// project keys are 2–6 letters (CreateProjectRequest)
ISSUEKEY : [a-z] [a-z] [a-z]? [a-z]? [a-z]? [a-z]? '-' [0-9]+ ;
// -7d, 2w, 12h, 30m (minutes, as in Jira); longer than the NUMBER it starts with, so it wins
RELDATE  : '-'? [0-9]+ [dwmh] ;
DATE     : [0-9] [0-9] [0-9] [0-9] '-' [0-9] [0-9] '-' [0-9] [0-9] ;
NUMBER   : '-'? [0-9]+ ('.' [0-9]+)? ;
// double-quoted; \" and \\ are the only escapes
STRING   : '"' ('\\' [\\"] | ~[\\"\r\n])* '"' ;
IDENT    : [a-z_] [a-z0-9_.]* ;

EQ     : '=' ;
NE     : '!=' ;
GE     : '>=' ;
GT     : '>' ;
LE     : '<=' ;
LT     : '<' ;
TILDE  : '~' ;
LPAREN : '(' ;
RPAREN : ')' ;
COMMA  : ',' ;

WS : [ \t\r\n]+ -> skip ;

// anything else (a stray '#', an unclosed quote) becomes one token the parser never accepts: the lexer
// itself never fails, and every mistake surfaces as ONE parser error with a position and what was expected
ERROR_CHAR : . ;
