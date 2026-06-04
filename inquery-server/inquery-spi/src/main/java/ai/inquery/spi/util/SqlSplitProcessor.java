/*
 * Copyright (c) 2023 OceanBase.
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
package ai.inquery.spi.util;

import com.alibaba.druid.DbType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 */
public class SqlSplitProcessor {

    private static final String DELIMITER_NAME = "delimiter";
    /**
     * Whether to preserve formatting
     */
    private boolean preserveFormat = false;
    private String delimiter = ";";
    @Getter
    private boolean mlComment = false;
    private char inString = '\0';
    private DbType dialectType;
    private char escapeString = '\0';
    private boolean inNormalSql = false;
    /**
     * Whether to preserve single-line comments
     */
    @Getter
    private boolean preserveSingleComments = false;
    /**
     * Whether to preserve multi-line comments
     */
    @Getter
    private boolean preserveMultiComments = false;

    private static Pattern pattern = Pattern.compile("\\r\\n|\\r|\\n");


    public SqlSplitProcessor(boolean preserveFormat, String delimiter) {
        this.delimiter = delimiter;
        this.preserveFormat = preserveFormat;
    }

    public SqlSplitProcessor(DbType dialectType,
                             boolean preserveSingleComments,
                             boolean preserveMultiComments) {
        this.preserveFormat = true;
        this.dialectType = dialectType;
        this.preserveSingleComments = preserveSingleComments;
        this.preserveMultiComments = preserveMultiComments;
    }

    public SqlSplitProcessor(DbType dialectType,
                             boolean preserveFormat,
                             boolean preserveSingleComments,
                             boolean preserveMultiComments) {
        this.preserveFormat = preserveFormat;
        this.dialectType = dialectType;
        this.preserveSingleComments = preserveSingleComments;
        this.preserveMultiComments = preserveMultiComments;
    }

    public SqlSplitProcessor(DbType dialectType, String delimiter) {
        this.preserveFormat = true;
        this.dialectType = dialectType;
        this.delimiter = delimiter;
    }

    public SqlSplitProcessor() {}

    public static SqlStatementIterator iterator(InputStream in, Charset charset, SqlSplitProcessor processor) {
        return new SqlCommentProcessorIterator(in, charset, processor);
    }

    public static List<SplitSqlString> removeSqlComments(String originalSql,
                                                         String delimiter, DbType dbMode, boolean preserveFormat) {
        SqlSplitProcessor sqlCommentProcessor = new SqlSplitProcessor(preserveFormat, delimiter);
        StringBuffer buffer = new StringBuffer();
        List<SplitSqlString> offsetStrings = new ArrayList<>();
        List<List<OrderChar>> lines = splitLine(originalSql);
        Holder<Integer> bufferOrder = new Holder<>(0);
        for (List<OrderChar> item : lines) {
            if (Objects.nonNull(dbMode) && DbType.mysql.equals(dbMode)) {
                sqlCommentProcessor.addLineMysql(offsetStrings, buffer, bufferOrder, item);
            } else {
                sqlCommentProcessor.addLineOracle(offsetStrings, buffer, bufferOrder, item);
            }
        }

        String bufferStr = buffer.toString();
        if (bufferStr.trim().length() != 0) {
            while (true) {
                if (bufferStr.endsWith("\n")) {
                    /**
                     * remove all <code>\n</code> from sqls
                     */
                    bufferStr = bufferStr.substring(0, bufferStr.length() - 1);
                } else {
                    break;
                }
            }
            if (offsetStrings.size() == 0) {
                offsetStrings.add(new SplitSqlString(0, bufferStr));
            } else {
                offsetStrings.add(new SplitSqlString(
                        offsetStrings.get(offsetStrings.size() - 1).getOffset()
                                + offsetStrings.get(offsetStrings.size() - 1).getStr().length(),
                        bufferStr));
            }
        }
        return offsetStrings;
    }

    public synchronized List<SplitSqlString> split(StringBuffer buffer, String sqlScript) {
        if (StringUtils.isBlank(sqlScript)) {
            return new ArrayList<>();
        }
        try {
            List<SplitSqlString> offsetStrings = new ArrayList<>();

            List<List<OrderChar>> lines = splitLine(sqlScript);
            Holder<Integer> bufferOrder = new Holder<>(0);
            int i = 0;
            for (List<OrderChar> item : lines) {
                if (Objects.nonNull(this.dialectType) && DbType.mysql.equals(this.dialectType)) {
                    addLineMysql(offsetStrings, buffer, bufferOrder, item);
                } else if (Objects.nonNull(this.dialectType) && DbType.mariadb.equals(this.dialectType)) {
                    addLineMysql(offsetStrings, buffer, bufferOrder, item);
                } else if (Objects.nonNull(this.dialectType) && DbType.oracle.equals(this.dialectType)) {
                    addLineOracle(offsetStrings, buffer, bufferOrder, item);
                } else if (Objects.nonNull(this.dialectType) && DbType.oceanbase.equals(this.dialectType)) {
                    addLineMysql(offsetStrings, buffer, bufferOrder, item);
                } else {
                    throw new IllegalArgumentException("dialect type is illegal");
                }
                i++;
            }
            return offsetStrings;
        } finally {
            mlComment = false;
            inString = '\0';
            inNormalSql = false;
        }
    }

    private synchronized void addLineMysql(List<SplitSqlString> sqls, StringBuffer buffer, Holder<Integer> bufferOrder,
                                           List<OrderChar> line) {
        int pos, out;
        boolean needSpace = false;
        // Flag to identify if currently in HINT or CONDITIONAL
        SSC ssComment = SSC.NONE;
        boolean isSameLine = false;
        int lineLength = line.size();
        OrderChar[] lines = line.toArray(new OrderChar[lineLength + 1]);
        if ((lines.length == 0 || lines[0] == null || lines[0].getCh() == 0) && buffer.length() == 0) {
            return;
        }
        lines[lineLength] = new OrderChar((char) 0, lineLength);
        for (pos = out = 0; pos < lineLength; pos++) {
            OrderChar inOrderChar = lines[pos];
            char inChar = inOrderChar.getCh();
            // Remove leading spaces from each SQL statement line
            if (inChar == ' ' && out == 0 && buffer.length() == 0 && !preserveFormat) {
                continue;
            }
            int delimiterBegin = 0;
            if (preserveFormat) {
                for (; delimiterBegin < out
                        && (lines[delimiterBegin].getCh() == ' '
                                || lines[delimiterBegin].getCh() == '\t'); delimiterBegin++) {
                }
            }
            if (equalsIgnoreCase((DELIMITER_NAME + " ").toCharArray(), lines, delimiterBegin, (out - delimiterBegin))) {
                // Detected "delimiter" string, not in multi-line comments or strings, indicates delimiter setting statement
                StringBuilder newDelimiter = new StringBuilder();
                for (; pos < lineLength; pos++) {
                    char tempChar = lines[pos].getCh();
                    if (tempChar != ' ') {
                        newDelimiter.append(tempChar);
                    } else if (newDelimiter.length() != 0) {
                        break;
                    }
                }
                out = 0;
                this.delimiter = newDelimiter.toString();
                continue;
            }
            // Scan escape character, possible command appearance
            if ((!mlComment && inChar == '\\')) {
                inOrderChar = lines[++pos];
                inChar = inOrderChar.getCh();
                if (inChar == 0) {
                    break;
                }
                if (inString != '\0' || inChar == 'N') {
                    lines[out++] = OrderChar.newOrderChar(lines[pos - 1]);
                    if (inChar == '`' && inString == inChar) {
                        pos--;
                    } else {
                        lines[out++] = OrderChar.newOrderChar(lines[pos]);
                    }
                    continue;
                }
                // Not mysql model or correct command not found, put escape symbol and escape character into buffer
                lines[out++] = OrderChar.newOrderChar(lines[pos - 1]);
                lines[out++] = OrderChar.newOrderChar(lines[pos]);
            } else if (!mlComment && inString == '\0' && ssComment != SSC.HINT
                    && isPrefix(lines, pos, delimiter)) {
                // Not multi-line comment, not in string, not hint and starts with delimiter, usually reached SQL end
                pos += delimiter.length();
                if (out != 0) {
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                }
                // buffer.append(";").append('\n');
                sqls.add(new SplitSqlString(bufferOrder.getValue(), buffer.toString()));
                bufferOrder.setValue(bufferOrder.getValue() + buffer.length());
                pos--;
                buffer.setLength(0);
                isSameLine = true;
                inNormalSql = false;
            } else if (!mlComment
                    && (inString == '\0' && (inChar == '#' || (inChar == '-' && lines[pos + 1].getCh() == '-'
                            && ((lines[pos + 2].getCh() == ' ' || lines[pos + 2].getCh() == '\0')))))) {
                // In single-line comment
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveSingleComments) {
                    // If preserving single-line comments, must completely copy comments to buffer without discarding
                    for (; pos < lineLength; pos++) {
                        lines[out++] = OrderChar.newOrderChar(lines[pos]);
                    }
                    if (isOnlyWhiteSpace(buffer)) {
                        // Buffer all spaces or empty means comment is at first line or after completed SQL statement
                        if (sqls.size() != 0) {
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            // Comment after completed SQL, already in SQL collection, append to last SQL
                            append(buffer, lines, 0, out);
                            int lastIndex = sqls.size() - 1;
                            String lastSql = sqls.get(lastIndex).getStr();
                            if (!isSameLine) {
                                lastSql += '\n';
                            }
                            lastSql += buffer + "\n";
                            sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                            buffer.setLength(0);
                        } else {
                            lines[out++].setCh('\n');
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            append(buffer, lines, 0, out - 1);
                        }
                    } else {
                        lines[out++].setCh('\n');
                        if (buffer.length() == 0) {
                            bufferOrder.setValue(lines[0].getOrder());
                        }
                        append(buffer, lines, 0, out - 1);
                    }
                    out = 0;
                }
                break;
            } else if (inString == '\0' && (inChar == '/' && lines[pos + 1].getCh() == '*')
            // Note: Oracle mode doesn't have Conditional, so we need to avoid it. MySQL Conditional should be treated as comment in Oracle mode
                    && lines[pos + 2].getCh() != '!'
                    && lines[pos + 2].getCh() != '+' && ssComment != SSC.HINT) {
                // In multi-line comment, avoided HINT and CONDITIONAL, no conditional in Oracle mode
                if (preserveMultiComments) {
                    lines[out++].setCh('/');
                    lines[out++].setCh('*');
                }
                pos++;
                mlComment = true;
            } else if (mlComment && ssComment == SSC.NONE && inChar == '*' && lines[pos + 1].getCh() == '/') {
                // Multi-line comment end
                pos++;
                mlComment = false;
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveMultiComments) {
                    lines[out++].setCh('*');
                    lines[out++].setCh('/');
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                    if (sqls.size() != 0 && !inNormalSql) {
                        int lastIndex = sqls.size() - 1;
                        String lastSql = sqls.get(lastIndex).getStr() + buffer;
                        sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                        buffer.setLength(0);
                    }
                }
                needSpace = true;
            } else {
                if (inString == '\0' && inChar == '/' && lines[pos + 1].getCh() == '*') {
                    if (lines[pos + 2].getCh() == '!') {
                        // In CONDITIONAL
                        ssComment = SSC.CONDITIONAL;
                    } else if (lines[pos + 2].getCh() == '+') {
                        // In HINT
                        ssComment = SSC.HINT;
                    }
                } else if (inString == '\0' && ssComment != SSC.NONE && inChar == '*'
                        && lines[pos + 1].getCh() == '/') {
                    // HINT or CONDITIONAL end
                    ssComment = SSC.NONE;
                }
                if (inChar == inString) {
                    // Character pointer out of string or expression
                    inString = '\0';
                } else if (!mlComment && inString == '\0' && ssComment != SSC.HINT
                        && (inChar == '\'' || inChar == '"' || inChar == '`')) {
                    // Character pointer enters string or expression
                    inString = inChar;
                }
                if (!mlComment) {
                    if (needSpace && inChar == ' ') {
                        lines[out++].setCh(' ');
                    }
                    needSpace = false;
                    // Normal SQL, put in line buffer, flush to buffer cache at proper time
                    lines[out++] = OrderChar.newOrderChar(inOrderChar);
                    if (inChar != ' ') {
                        inNormalSql = true;
                    }
                } else if (preserveMultiComments) {
                    // Preserve multi-line comments
                    lines[out++] = OrderChar.newOrderChar(inOrderChar);
                }
            }
        }
        // Interceptive handling: if out pointer is not 0, there is content in lines not flushed to buffer, flush here
        if (out != 0 || buffer.length() != 0) {
            lines[out++].setCh('\n');
            if (buffer.length() == 0) {
                bufferOrder.setValue(lines[0].getOrder());
            }
            append(buffer, lines, 0, out);
        }
    }

    private boolean isOnlyWhiteSpace(StringBuffer buffer) {
        if (buffer == null) {
            return false;
        }
        int length = buffer.length();
        for (int i = 0; i < length; i++) {
            if (buffer.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    public synchronized void addLineOracle(List<SplitSqlString> sqls, StringBuffer buffer, Holder<Integer> bufferOrder,
                                           List<OrderChar> line) {
        int pos, out;
        boolean needSpace = false;
        // Flag to identify if currently in HINT or CONDITIONAL
        SSC ssComment = SSC.NONE;

        boolean isSameLine = false;
        int lineLength = line.size();
        OrderChar[] lines = line.toArray(new OrderChar[lineLength + 1]);
        if ((lines.length == 0 || lines[0] == null || lines[0].getCh() == 0) && buffer.length() == 0) {
            return;
        }
        lines[lineLength] = new OrderChar((char) 0, lineLength);
        for (pos = out = 0; pos < lineLength; pos++) {
            OrderChar inOrderChar = lines[pos];
            char inChar = inOrderChar.getCh();
            // Remove leading spaces from each SQL statement line
            if (inChar == ' ' && out == 0 && buffer.length() == 0 && !preserveFormat) {
                continue;
            }
            int delimiterBegin = 0;
            if (preserveFormat) {
                for (; delimiterBegin < out
                        && (lines[delimiterBegin].getCh() == ' '
                                || lines[delimiterBegin].getCh() == '\t'); delimiterBegin++) {
                }
            }
            if (equalsIgnoreCase((DELIMITER_NAME + " ").toCharArray(), lines, delimiterBegin, (out - delimiterBegin))) {
                // Detected "delimiter" string, not in multi-line comments or strings, indicates delimiter setting statement
                StringBuilder newDelimiter = new StringBuilder();
                for (; pos < lineLength; pos++) {
                    char tempChar = lines[pos].getCh();
                    if (tempChar != ' ') {
                        newDelimiter.append(tempChar);
                    } else if (newDelimiter.length() != 0) {
                        break;
                    }
                }
                out = 0;
                this.delimiter = newDelimiter.toString();
                continue;
            }
            if (!mlComment && inString == '\0' && ssComment != SSC.HINT && isPrefix(lines, pos, delimiter)) {
                // Not multi-line comment, not in string, not hint and starts with delimiter, usually reached SQL end
                pos += delimiter.length();
                if (out != 0) {
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                }
                // buffer.append(";").append('\n');
                sqls.add(new SplitSqlString(bufferOrder.getValue(), buffer.toString()));
                bufferOrder.setValue(bufferOrder.getValue() + buffer.length());
                pos--;
                buffer.setLength(0);
                isSameLine = true;
                inNormalSql = false;
            } else if (!mlComment && (inString == '\0' && (inChar == '-' && lines[pos + 1].getCh() == '-'
                    && (lines[pos + 2].getCh() != '+' || (lines[pos + 2].getCh() == ' '
                            || lines[pos + 2].getCh() == '\0'))))) {
                // In single-line comment, avoid single-line HINT
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveSingleComments) {
                    // If preserving single-line comments, must completely copy comments to buffer without discarding
                    for (; pos < lineLength; pos++) {
                        lines[out++] = OrderChar.newOrderChar(lines[pos]);
                    }
                    if (isOnlyWhiteSpace(buffer)) {
                        // Buffer all spaces or empty means comment is at first line or after completed SQL statement
                        if (sqls.size() != 0) {
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            // Comment after completed SQL, already in SQL collection, append to last SQL
                            append(buffer, lines, 0, out);
                            int lastIndex = sqls.size() - 1;
                            String lastSql = sqls.get(lastIndex).getStr();
                            if (!isSameLine) {
                                lastSql += '\n';
                            }
                            lastSql += buffer + "\n";
                            sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                            buffer.setLength(0);
                        } else {
                            lines[out++].setCh('\n');
                            if (buffer.length() == 0) {
                                bufferOrder.setValue(lines[0].getOrder());
                            }
                            append(buffer, lines, 0, out - 1);
                        }
                    } else {
                        lines[out++].setCh('\n');
                        if (buffer.length() == 0) {
                            bufferOrder.setValue(lines[0].getOrder());
                        }
                        append(buffer, lines, 0, out - 1);
                    }
                    out = 0;
                }
                break;
            } else if (inString == '\0' && (inChar == '/' && lines[pos + 1].getCh() == '*')
                    && lines[pos + 2].getCh() != '+'
                    && ssComment != SSC.HINT) {
                // In multi-line comment, avoided HINT and CONDITIONAL, no conditional in Oracle mode
                if (preserveMultiComments) {
                    lines[out++].setCh('/');
                    lines[out++].setCh('*');
                }
                pos++;
                mlComment = true;
            } else if (mlComment && ssComment == SSC.NONE && inChar == '*' && lines[pos + 1].getCh() == '/') {
                // Multi-line comment end
                pos++;
                mlComment = false;
                if (buffer.length() == 0) {
                    bufferOrder.setValue(lines[0].getOrder());
                }
                append(buffer, lines, 0, out);
                out = 0;
                if (preserveMultiComments) {
                    lines[out++].setCh('*');
                    lines[out++].setCh('/');
                    if (buffer.length() == 0) {
                        bufferOrder.setValue(lines[0].getOrder());
                    }
                    append(buffer, lines, 0, out);
                    out = 0;
                    if (sqls.size() != 0 && !inNormalSql) {
                        int lastIndex = sqls.size() - 1;
                        String lastSql = sqls.get(lastIndex).getStr() + buffer;
                        sqls.set(lastIndex, new SplitSqlString(sqls.get(lastIndex).getOffset(), lastSql));
                        buffer.setLength(0);
                    }
                }
                needSpace = true;
            } else {
                if (inString == '\0' && inChar == '/' && lines[pos + 1].getCh() == '*') {
                    if (lines[pos + 2].getCh() == '+') {
                        // In HINT
                        ssComment = SSC.HINT;
                    }
                } else if (inString == '\0' && ssComment != SSC.NONE && inChar == '*'
                        && lines[pos + 1].getCh() == '/') {
                    // HINT or CONDITIONAL end
                    ssComment = SSC.NONE;
                } else if (inString == '\0' && inChar == '-' && lines[pos + 1].getCh() == '-'
                        && lines[pos + 2].getCh() == '+') {
                    // In Oracle mode, Hints can be single-line or multi-line; this handles single-line Hint in Oracle mode
                    ssComment = SSC.HINT;
                }
                if (inChar == inString) {
                    // Character pointer out of string or expression
                    if (escapeString == '\0') {
                        inString = '\0';
                    } else if (pos >= 1 && matchQEscape(lines[pos - 1].getCh())) {
                        inString = '\0';
                        escapeString = '\0';
                    }
                } else if (!mlComment && inString == '\0' && ssComment != SSC.HINT
                        && (inChar == '\'' || inChar == '"' || inChar == '`')) {
                    // Character pointer enters string or expression
                    inString = inChar;
                    if (pos >= 1 && (lines[pos - 1].getCh() == 'q' || lines[pos - 1].getCh() == 'Q')) {
                        // Oracle-specific syntax, Q-escape
                        escapeString = lines[pos + 1].getCh();
                    }
                }
                if (!mlComment) {
                    if (needSpace && inChar == ' ') {
                        lines[out++].setCh(' ');
                    }
                    needSpace = false;
                    // Normal SQL, put in line buffer, flush to buffer cache at proper time
                    lines[out++] = new OrderChar(inOrderChar.getCh(), inOrderChar.getOrder());
                    if (inChar != ' ') {
                        inNormalSql = true;
                    }
                } else if (preserveMultiComments) {
                    // Preserve multi-line comments
                    lines[out++] = new OrderChar(inOrderChar.getCh(), inOrderChar.getOrder());
                }
            }
        }
        // Interceptive handling: if out pointer is not 0, there is content in lines not flushed to buffer, flush here
        if (out != 0 || buffer.length() != 0) {
            lines[out++].setCh('\n');
            if (buffer.length() == 0) {
                bufferOrder.setValue(lines[0].getOrder());
            }
            append(buffer, lines, 0, out);
        }
    }

    private boolean equalsIgnoreCase(char[] src, OrderChar[] dest, int begin, int count) {
        if (src == null && dest == null) {
            return true;
        } else if (src != null && dest != null) {
            if (src.length != count) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                char c1 = src[i];
                char c2 = dest[begin + i].getCh();
                if (c1 == c2) {
                    continue;
                }
                char u1 = Character.toUpperCase(c1);
                char u2 = Character.toUpperCase(c2);
                if (u1 == u2) {
                    continue;
                }
                if (Character.toLowerCase(u1) == Character.toLowerCase(u2)) {
                    continue;
                }
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Whether current SQL starts with a delimiter
     */
    private boolean isPrefix(OrderChar[] line, int pos, String delim) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < line.length - pos; i++) {
            builder.append(line[pos + i].getCh());
        }
        boolean res = builder.toString().startsWith(delim);
        if (!res || !"/".equals(delim) || line.length <= 1) {
            return res;
        }
        // Matched delimiter: if delimiter is forward slash and current line length > 1, need to avoid multi-line comments
        if (pos == 0) {
            return !(line[pos + 1].getCh() == '*');
        } else if (line.length - 1 == pos) {
            return !(line[pos - 1].getCh() == '*');
        }
        return !(line[pos + 1].getCh() == '*' || line[pos - 1].getCh() == '*');
    }

    private boolean matchQEscape(char escapeChar) {
        if (this.escapeString == '\0') {
            return false;
        }
        switch (this.escapeString) {
            case '<':
                return escapeChar == '>';
            case '{':
                return escapeChar == '}';
            case '[':
                return escapeChar == ']';
            case '(':
                return escapeChar == ')';
            default:
                return this.escapeString == escapeChar;
        }
    }

    private void append(StringBuffer buffer, OrderChar[] chars, int begin, int count) {
        for (int i = begin; i < count; i++) {
            buffer.append(chars[i].getCh());
        }
    }

    private static List<List<OrderChar>> splitLine(String sqlScript) {
        List<List<OrderChar>> lines = new ArrayList<>();
        List<OrderChar> currentList = new ArrayList<>();
        Matcher matcher = pattern.matcher(sqlScript);
        int start = 0;
        while (matcher.find()) {
            int end = matcher.start();
            for (int i = start; i < end; i++) {
                OrderChar orderChar = new OrderChar(sqlScript.charAt(i), i);
                currentList.add(orderChar);
            }
            lines.add(currentList);
            currentList = new ArrayList<>();
            start = matcher.end();
        }
        if (start < sqlScript.length()) {
            for (int i = start; i < sqlScript.length(); i++) {
                OrderChar orderChar = new OrderChar(sqlScript.charAt(i), i);
                currentList.add(orderChar);
            }
        }
        if (!currentList.isEmpty()) {
            lines.add(currentList);
        }
        return lines;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    private enum SSC {
        /**
         * Not in HINT or CONDITIONAL
         */
        NONE(0),
        /**
         * Current SQL char pointer is in CONDITIONAL
         */
        CONDITIONAL(1),
        /**
         * Currently in HINT
         */
        HINT(2);

        private final int value;

        SSC(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static class SqlCommentProcessorIterator implements SqlStatementIterator {

        private final BufferedReader reader;
        private final StringBuffer buffer = new StringBuffer();
        private final LinkedList<SplitSqlString> holder = new LinkedList<>();
        private final Holder<Integer> bufferOrder = new Holder<>(0);
        private final SqlSplitProcessor processor;

        private SplitSqlString current;
        private int lastLineOrder = 0;
        private long iteratedBytes = 0;

        public SqlCommentProcessorIterator(InputStream input, Charset charset, SqlSplitProcessor processor) {
            this.reader = new BufferedReader(new InputStreamReader(input, charset));
            this.processor = processor;
        }

        @Override
        public boolean hasNext() {
            if (current == null) {
                current = parseNext();
            }
            return current != null;
        }

        @Override
        public SplitSqlString next() {
            SplitSqlString next = current;
            current = null;
            if (next == null) {
                next = parseNext();
                if (next == null) {
                    throw new NoSuchElementException("No more available sql.");
                }
            }
            return next;
        }

        @Override
        public long iteratedBytes() {
            return iteratedBytes;
        }

        private SplitSqlString parseNext() {
            try {
                if (!holder.isEmpty()) {
                    return holder.poll();
                }
                String line;
                while (holder.isEmpty() && (line = reader.readLine()) != null) {
                    if ( DbType.mysql.equals(processor.dialectType)) {
                        processor.addLineMysql(holder, buffer, bufferOrder, line.chars()
                                .mapToObj(c -> new OrderChar((char) c, lastLineOrder++))
                                .collect(Collectors.toList()));
                    } else if (DbType.oracle.equals(processor.dialectType)) {
                        processor.addLineOracle(holder, buffer, bufferOrder, line.chars()
                                .mapToObj(c -> new OrderChar((char) c, lastLineOrder++))
                                .collect(Collectors.toList()));
                    } else if (DbType.oceanbase.equals(processor.dialectType)) {
                        processor.addLineMysql(holder, buffer, bufferOrder, line.chars()
                                .mapToObj(c -> new OrderChar((char) c, lastLineOrder++))
                                .collect(Collectors.toList()));
                    }
                    // consider \n in the end of each line
                    lastLineOrder++;
                    iteratedBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                }
                if (!holder.isEmpty()) {
                    return holder.poll();
                }
                if (buffer.toString().trim().isEmpty()) {
                    return null;
                }
                String sql = buffer.toString();
                buffer.setLength(0);
                return new SplitSqlString(0, sql);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse input. reason: " + e.getMessage(), e);
            }
        }

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class OrderChar {
        private char ch;
        private int order;

        static OrderChar newOrderChar(OrderChar orderChar) {
            return new OrderChar(orderChar.getCh(), orderChar.getOrder());
        }
    }

}
