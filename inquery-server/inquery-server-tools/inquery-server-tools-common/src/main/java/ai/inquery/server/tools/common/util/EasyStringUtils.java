package ai.inquery.server.tools.common.util;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * String utility class
 *
 */
public class EasyStringUtils {
    /**
     * 0 characters
     */
    private static final char ZERO_CHAR = '0';

    /**
     * Remove the 0 in front of the job number
     *
     * @param userId employee ID
     * @return modified job number
     */
    public static String cutUserId(String userId) {
        if (!StringUtils.isNumeric(userId)) {
            return userId;
        }
        int startIndex = 0;
        for (int i = 0; i < userId.length(); i++) {
            char c = userId.charAt(i);
            // Query the first position that is not 0
            if (ZERO_CHAR == c) {
                startIndex = i + 1;
            } else {
                break;
            }
        }
        // Maybe the entire account is 0
        if (startIndex == userId.length()) {
            return "0";
        }
        return userId.substring(startIndex);
    }

    /**
     * Remove the job number after the flower name
     *
     * @param name name or nickname
     * @return the name or nickname after removing the work number
     */
    public static String cutName(String name, String workNo) {
        if (StringUtils.isBlank(workNo) || StringUtils.isBlank(name)) {
            return name;
        }
        // There may be 0 knots here
        String cutName = RegExUtils.removeFirst(name, workNo);
        int lastIndex = cutName.length();
        for (int i = cutName.length() - 1; i >= 0; i--) {
            char c = cutName.charAt(i);
            // Query the last position that is not 0
            if (ZERO_CHAR == c) {
                lastIndex = i;
            } else {
                break;
            }
        }
        return cutName.substring(0, lastIndex);
    }

    /**
     * Add 0 in front of the job number
     *
     * @param userId employee ID
     * @return modified job number
     */
    public static String padUserId(String userId) {
        if (!StringUtils.isNumeric(userId)) {
            return userId;
        }
        return StringUtils.leftPad(userId, 6, '0');
    }

    /**
     * Build the name of the display
     *
     * @param name     name
     * @param nickName flower name
     * @return display name name (flower name)
     */
    public static String buildShowName(String name, String nickName) {
        StringBuilder showName = new StringBuilder();
        if (StringUtils.isNotBlank(name)) {
            showName.append(name);
        }
        if (StringUtils.isNotBlank(nickName)) {
            showName.append("(");
            showName.append(nickName);
            showName.append(")");
        }
        return showName.toString();
    }

    /**
     * Splice multiple strings together
     *
     * @param delimiter delimiter cannot be empty
     * @param elements  string can be empty and empty strings will be ignored
     * @return
     */
    public static String join(CharSequence delimiter, CharSequence... elements) {
        if (elements == null) {
            return null;
        }
        List<CharSequence> charSequenceList = Arrays.stream(elements).filter(
                org.apache.commons.lang3.StringUtils::isNotBlank).collect(Collectors.toList());
        if (charSequenceList.isEmpty()) {
            return null;
        }
        return String.join(delimiter, charSequenceList);
    }

    /**
     * Limit the length of a string string. If it exceeds the length, it will be replaced with...
     *
     * @param str    string
     * @param length limit length
     * @return
     */
    public static String limitString(String str, int length) {
        if (Objects.isNull(str)) {
            return null;
        }
        String limitString = StringUtils.substring(str, 0, length);
        if (limitString.length() == length) {
            limitString += "...";
        }
        return limitString;
    }

    /**
     * Escapes specific characters in a string.
     * <p>
     * Based on the provided escape mapping, this method iterates through each character in the input string.
     * If a character exists in the mapping, the corresponding escape character is inserted before it; otherwise the character remains unchanged.
     * Commonly used to create strings that comply with specific format requirements, such as SQL string escaping or adding special markers to text.
     *
     * @param str       The target string to escape.
     * @param escapeMap The escape mapping where keys are characters to escape and values are the escape characters.
     * @return The escaped string with escape characters inserted.
     */
    public static String escapeString(@NotNull String str, Map<Character, Character> escapeMap) {
        if (str == null) {
            return null;
        }
        if (StringUtils.isBlank(str)) {
            return str;
        }


        StringBuilder escapedString = new StringBuilder(str.length() * 2);

        for (char c : str.toCharArray()) {
            if (escapeMap.containsKey(c)) {
                escapedString.append(escapeMap.get(c)).append(c);
            } else {
                escapedString.append(c);
            }
        }
        return escapedString.toString();
    }

    public static String escapeString(String str) {
        HashMap<Character, Character> escapeMap = Maps.newHashMapWithExpectedSize(2);
        // (char)39 -> '
        escapeMap.put((char) 39, (char) 39);
        // (char)92 -> \
        escapeMap.put((char) 92, (char) 92);
        return escapeString(str, escapeMap);
    }

    /**
     * @param value str="'abc\"
     * @return "'''abc\\'"
     */
    public static String escapeAndQuoteString(String value) {
        return quoteString(escapeString(value));
    }

    /**
     * @param value     "abcd"
     * @param quoteChar '%'
     * @return "%abcd%"
     */
    public static String quoteString(String value, char quoteChar) {
        return quoteChar + value + quoteChar;
    }

    /**
     * @param value "abcd"
     * @return "'abcd'"
     */
    public static String quoteString(String value) {
        // (char)39 -> '
        return quoteString(value, (char) 39);
    }

    public static String getBitString(byte[] bytes, final int precision) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder(precision);
        for (byte b : bytes) {
            builder.append(Integer.toBinaryString(b & 0xFF));
        }

        // Get the complete binary string
        String bitString = builder.toString();

        // Pad leading zeros to match the required total length
        bitString = Strings.padStart(bitString, precision, '0');

        return bitString;
    }


    public static String escapeLineString(String str) {
        if (StringUtils.isBlank(str)) {
            return str;
        }
        return str;
        // TODO Need to be implemented in the future with different data types
//        return str.replace("\r\n", "\\r\\n")
//                .replace("\n", "\\n")
//                .replace("\r", "\\r");
    }


    public static String sqlEscape(String str) {
        if (StringUtils.isBlank(str)) {
            return str;
        }
        str = str.trim();
        if (str.endsWith(";")) {
            str = str.substring(0, str.length() - 1);
        }
        return str;
    }


}
