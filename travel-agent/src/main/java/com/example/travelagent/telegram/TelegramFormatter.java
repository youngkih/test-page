package com.example.travelagent.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude 가 쓴 마크다운을 텔레그램이 지원하는 HTML(b, a, code, pre 등 일부 태그만)로 바꾸고,
 * 텔레그램 메시지 길이 제한(4,096자)에 맞게 나눈다.
 *
 * <p>변환이 완벽하지 않아도 괜찮다. 텔레그램이 HTML 해석에 실패하면 원문 그대로 다시 보낸다.
 */
final class TelegramFormatter {

    /** 텔레그램 한도는 4,096자. HTML 태그와 이스케이프로 늘어나는 만큼 여유를 둔다. */
    static final int CHUNK_SIZE = 3500;

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}.*$");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    // http(s) 링크만 변환한다 (javascript: 같은 링크는 글자로 남김)
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private TelegramFormatter() {
    }

    static String toHtml(String markdown) {
        StringBuilder out = new StringBuilder();
        List<String> pre = new ArrayList<>(); // 코드 블록 또는 표 줄 모음
        boolean inFence = false;

        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                if (inFence) {
                    flushPre(out, pre);
                }
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                pre.add(line);
                continue;
            }
            if (line.strip().startsWith("|")) {
                if (!TABLE_SEPARATOR.matcher(line).matches()) {
                    pre.add(line.strip());
                }
                continue;
            }
            flushPre(out, pre);

            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            if (heading.matches()) {
                out.append("<b>").append(inline(heading.group(1).replace("**", ""))).append("</b>");
            } else if (RULE.matcher(line).matches()) {
                out.append("──────────");
            } else if (bullet.matches()) {
                out.append(bullet.group(1)).append("• ").append(inline(bullet.group(2)));
            } else {
                out.append(inline(line));
            }
            out.append('\n');
        }
        flushPre(out, pre);
        return out.toString().strip();
    }

    private static void flushPre(StringBuilder out, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        out.append("<pre>").append(escape(String.join("\n", lines))).append("</pre>\n");
        lines.clear();
    }

    /** 한 줄 안의 강조·링크·코드 변환. 먼저 전부 이스케이프한 뒤 우리가 만든 태그만 넣는다. */
    static String inline(String text) {
        String html = escape(text);
        html = CODE.matcher(html).replaceAll("<code>$1</code>");
        html = LINK.matcher(html).replaceAll("<a href=\"$2\">$1</a>");
        html = BOLD.matcher(html).replaceAll("<b>$1</b>");
        return html;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 문단(빈 줄) 경계를 우선으로 maxLength 이하 조각으로 나눈다. */
    static List<String> split(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            // 한 줄이 너무 길면 강제로 자른다
            while (line.length() > maxLength) {
                flushChunk(chunks, current);
                chunks.add(line.substring(0, maxLength));
                line = line.substring(maxLength);
            }
            if (current.length() + line.length() + 1 > maxLength) {
                // 가능하면 마지막 빈 줄(문단 경계)에서 자른다
                int paragraphEnd = current.lastIndexOf("\n\n");
                if (paragraphEnd > maxLength / 2) {
                    String rest = current.substring(paragraphEnd + 2);
                    current.setLength(paragraphEnd);
                    flushChunk(chunks, current);
                    current.append(rest);
                } else {
                    flushChunk(chunks, current);
                }
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        flushChunk(chunks, current);
        return chunks;
    }

    private static void flushChunk(List<String> chunks, StringBuilder current) {
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().strip());
        }
        current.setLength(0);
    }
}
