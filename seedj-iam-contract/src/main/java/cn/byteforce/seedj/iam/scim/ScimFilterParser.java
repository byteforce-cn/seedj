package cn.byteforce.seedj.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recursive-descent parser for the RFC 7644 filter subset: {@code eq ne co sw ew pr gt lt ge le},
 * {@code and or not}, and parentheses. Quoted values are treated as literals; they are never
 * interpreted as SQL.
 */
public final class ScimFilterParser {

    private ScimFilterParser() {}

    public static ScimFilter parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Parser parser = new Parser(input);
        ScimFilter filter = parser.parseOr();
        if (parser.peek().type() != TokenType.EOF) {
            throw invalid("unexpected token '" + parser.peek().text() + "'");
        }
        return filter;
    }

    private static IamException invalid(String message) {
        return new IamException(
                IamErrorCode.SCIM_FILTER_INVALID, "invalid SCIM filter: " + message);
    }

    private enum TokenType {
        WORD,
        STRING,
        LPAREN,
        RPAREN,
        EOF
    }

    private record Token(TokenType type, String text) {}

    private static final class Parser {
        private final Lexer lexer;
        private Token current;

        private Parser(String input) {
            this.lexer = new Lexer(input);
            this.current = lexer.next();
        }

        private ScimFilter parseOr() {
            ScimFilter left = parseAnd();
            List<ScimFilter> parts = new ArrayList<>();
            parts.add(left);
            while (matchWord("or")) {
                parts.add(parseAnd());
            }
            return parts.size() == 1 ? left : new ScimFilter.Or(parts);
        }

        private ScimFilter parseAnd() {
            ScimFilter left = parseNot();
            List<ScimFilter> parts = new ArrayList<>();
            parts.add(left);
            while (matchWord("and")) {
                parts.add(parseNot());
            }
            return parts.size() == 1 ? left : new ScimFilter.And(parts);
        }

        private ScimFilter parseNot() {
            if (matchWord("not")) {
                return new ScimFilter.Not(parseNot());
            }
            return parsePrimary();
        }

        private ScimFilter parsePrimary() {
            if (match(TokenType.LPAREN)) {
                ScimFilter filter = parseOr();
                expect(TokenType.RPAREN, ")");
                return filter;
            }
            String attribute = expectWord("attribute");
            String operator = expectWord("operator").toLowerCase(Locale.ROOT);
            if ("pr".equals(operator)) {
                return new ScimFilter.Present(attribute);
            }
            ScimComparisonOperator comparison =
                    switch (operator) {
                        case "eq" -> ScimComparisonOperator.EQ;
                        case "ne" -> ScimComparisonOperator.NE;
                        case "co" -> ScimComparisonOperator.CO;
                        case "sw" -> ScimComparisonOperator.SW;
                        case "ew" -> ScimComparisonOperator.EW;
                        case "gt" -> ScimComparisonOperator.GT;
                        case "lt" -> ScimComparisonOperator.LT;
                        case "ge" -> ScimComparisonOperator.GE;
                        case "le" -> ScimComparisonOperator.LE;
                        default -> throw invalid("unsupported operator '" + operator + "'");
                    };
            return new ScimFilter.Compare(attribute, comparison, parseValue());
        }

        private Object parseValue() {
            Token token = current;
            if (token.type() == TokenType.STRING) {
                current = lexer.next();
                return token.text();
            }
            if (token.type() == TokenType.WORD) {
                current = lexer.next();
                String text = token.text();
                if ("true".equalsIgnoreCase(text)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(text)) {
                    return Boolean.FALSE;
                }
                if ("null".equalsIgnoreCase(text)) {
                    return null;
                }
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    try {
                        return Double.parseDouble(text);
                    } catch (NumberFormatException ignoredAgain) {
                        return text;
                    }
                }
            }
            throw invalid("expected a value");
        }

        private boolean matchWord(String word) {
            if (current.type() == TokenType.WORD && current.text().equalsIgnoreCase(word)) {
                current = lexer.next();
                return true;
            }
            return false;
        }

        private boolean match(TokenType type) {
            if (current.type() == type) {
                current = lexer.next();
                return true;
            }
            return false;
        }

        private String expectWord(String what) {
            if (current.type() != TokenType.WORD) {
                throw invalid("expected " + what);
            }
            String text = current.text();
            current = lexer.next();
            return text;
        }

        private void expect(TokenType type, String what) {
            if (current.type() != type) {
                throw invalid("expected '" + what + "'");
            }
            current = lexer.next();
        }

        private Token peek() {
            return current;
        }
    }

    private static final class Lexer {
        private final String input;
        private int index;

        private Lexer(String input) {
            this.input = input;
        }

        private Token next() {
            skipWhitespace();
            if (index >= input.length()) {
                return new Token(TokenType.EOF, "");
            }
            char c = input.charAt(index);
            if (c == '(') {
                index++;
                return new Token(TokenType.LPAREN, "(");
            }
            if (c == ')') {
                index++;
                return new Token(TokenType.RPAREN, ")");
            }
            if (c == '"') {
                index++;
                StringBuilder value = new StringBuilder();
                while (index < input.length()) {
                    char ch = input.charAt(index++);
                    if (ch == '\\' && index < input.length()) {
                        value.append(input.charAt(index++));
                    } else if (ch == '"') {
                        return new Token(TokenType.STRING, value.toString());
                    } else {
                        value.append(ch);
                    }
                }
                throw invalid("unterminated string literal");
            }
            int start = index;
            while (index < input.length()) {
                char ch = input.charAt(index);
                if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == '"') {
                    break;
                }
                index++;
            }
            String word = input.substring(start, index);
            if (word.isEmpty()) {
                throw invalid("unexpected character '" + c + "'");
            }
            return new Token(TokenType.WORD, word);
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }
    }
}
