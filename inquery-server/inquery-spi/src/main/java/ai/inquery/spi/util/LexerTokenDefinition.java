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

/**
 * SQL syntax token definitions.<br>
 * If a token is not supported, the corresponding method returns {@link org.antlr.v4.runtime.Token#MIN_USER_TOKEN_TYPE}
 */
interface LexerTokenDefinition {
    /**
     * Division operator
     */
    int DIV();

    /**
     * Whitespace characters (spaces, tabs, newlines, etc.)
     */
    int SPACES();

    /**
     * OB grammar treats single-line and multi-line comments uniformly as ANTLR_SKIP
     */
    int ANTLR_SKIP();

    /**
     * Single-line comment
     */
    int SINGLE_LINE_COMMENT();

    /**
     * Multi-line comment
     */
    int MULTI_LINE_COMMENT();

    /**
     * DECLARE keyword
     */
    int DECLARE();

    /**
     * BEGIN keyword
     */
    int BEGIN();

    /**
     * END keyword
     */
    int END();

    /**
     * CREATE keyword
     */
    int CREATE();

    /**
     * OR keyword
     */
    int OR();

    /**
     * REPLACE keyword
     */
    int REPLACE();

    /**
     * EDITIONABLE keyword
     */
    int EDITIONABLE();

    /**
     * NONEDITIONABLE keyword
     */
    int NONEDITIONABLE();

    /**
     * PROCEDURE keyword
     */
    int PROCEDURE();

    /**
     * FUNCTION keyword
     */
    int FUNCTION();

    /**
     * PACKAGE keyword
     */
    int PACKAGE();

    /**
     * TYPE keyword
     */
    int TYPE();

    /**
     * TRIGGER keyword
     */
    int TRIGGER();

    /**
     * BODY keyword
     */
    int BODY();

    /**
     * IDENT, may {@link #REGULAR_ID} OR {@link #DELIMITED_ID}
     */
    int IDENT();

    int REGULAR_ID();

    int DELIMITED_ID();

    int FOR();

    /**
     * LOOP keyword
     */
    int LOOP();

    /**
     * IF keyword
     */
    int IF();

    /**
     * CASE keyword
     */
    int CASE();

    /**
     * LANGUAGE keyword
     */
    int LANGUAGE();

    /**
     * EXTERNAL keyword
     */
    int EXTERNAL();

    /**
     * IS keyword
     */
    int IS();

    /**
     * AS keyword
     */
    int AS();

    /**
     * MEMBER keyword
     */
    int MEMBER();

    /**
     * STATIC keyword
     */
    int STATIC();

    int SEMICOLON();

    int ELSE();

    int THEN();

    int RIGHTBRACKET();

    int LEFTBRACKET();

    int GREATER_THAN_OP();

    int WHILE();
}
