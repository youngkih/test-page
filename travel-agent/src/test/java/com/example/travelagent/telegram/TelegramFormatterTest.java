package com.example.travelagent.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TelegramFormatterTest {

    @Test
    void 제목_굵게_목록_링크_코드를_변환한다() {
        String html = TelegramFormatter.toHtml("""
                ### 1일차
                - **이치란** 라멘 [지도](https://maps.google.com/?q=a&b=c)
                - 요금 `¥1,000`
                ---
                끝""");

        assertThat(html).isEqualTo("""
                <b>1일차</b>
                • <b>이치란</b> 라멘 <a href="https://maps.google.com/?q=a&amp;b=c">지도</a>
                • 요금 <code>¥1,000</code>
                ──────────
                끝""");
    }

    @Test
    void 위험한_문자와_링크는_글자로_남긴다() {
        String html = TelegramFormatter.toHtml("<script>alert(1)</script> [클릭](javascript:alert(1))");

        assertThat(html).doesNotContain("<script>").doesNotContain("<a ")
                .contains("&lt;script&gt;");
    }

    @Test
    void 표는_고정폭_블록으로_보낸다() {
        String html = TelegramFormatter.toHtml("""
                | 사이트 | 가격 |
                |---|---|
                | Agoda | 598,000원 |
                다음 문단""");

        assertThat(html).isEqualTo("<pre>| 사이트 | 가격 |\n| Agoda | 598,000원 |</pre>\n다음 문단");
    }

    @Test
    void 긴_글은_문단_경계에서_나눈다() {
        String paragraph = "가".repeat(40);
        String text = String.join("\n\n", List.of(paragraph, paragraph, paragraph));

        List<String> chunks = TelegramFormatter.split(text, 90);

        assertThat(chunks).containsExactly(paragraph + "\n\n" + paragraph, paragraph);
        assertThat(TelegramFormatter.split("나".repeat(250), 100)).extracting(String::length)
                .containsExactly(100, 100, 50);
    }
}
