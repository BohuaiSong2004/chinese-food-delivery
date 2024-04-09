package com.wokflow.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HighlighterTest {
    static final String S = SearchService.MARK_START;
    static final String E = SearchService.MARK_END;

    @Test
    void marksWholeWordsByPrefixAndTypo() {
        assertThat(Highlighter.of("mapo tofu").mark("Mapo Tofu")).isEqualTo(S + "Mapo" + E + " " + S + "Tofu" + E);
        assertThat(Highlighter.of("dump").mark("Shrimp Dumplings")).isEqualTo("Shrimp " + S + "Dumplings" + E);
        assertThat(Highlighter.of("tofo").mark("Silken tofu, minced beef")).isEqualTo("Silken " + S + "tofu" + E + ", minced beef");
        assertThat(Highlighter.of("nodle").mark("Wonton Noodle Soup")).isEqualTo("Wonton " + S + "Noodle" + E + " Soup");
        assertThat(Highlighter.of("dumplng").mark("Taro Dumplings")).isEqualTo("Taro " + S + "Dumplings" + E);
    }

    @Test
    void shortWordsNeedAnExactStartAndStopWordsAreIgnored() {
        assertThat(Highlighter.of("ox").mark("Box set")).isNull();           // 2 letters: no typo allowed, not a word start
        assertThat(Highlighter.of("salt and pepper").mark("Sweet and Sour")).isNull();
        assertThat(Highlighter.of("salt and pepper").mark("White pepper"))
                .isEqualTo("White " + S + "pepper" + E);
    }

    @Test
    void synonymsAreMarkedToo() {
        assertThat(Highlighter.of("boba").mark("Bubble Milk Tea")).contains(S + "Bubble" + E).contains(S + "Tea" + E);
        assertThat(Highlighter.of("馄饨").mark("红油抄手")).isEqualTo("红油" + S + "抄手" + E);
    }

    @Test
    void chineseByAdjacentPairsOrASingleCharacter() {
        assertThat(Highlighter.of("牛肉面").mark("兰州牛肉拉面")).isEqualTo("兰州" + S + "牛肉" + E + "拉面");
        assertThat(Highlighter.of("牛肉面").mark("红烧牛肉面")).isEqualTo("红烧" + S + "牛肉面" + E);
        assertThat(Highlighter.of("面").mark("担担面")).isEqualTo("担担" + S + "面" + E);
        assertThat(Highlighter.of("麻婆 tofu").mark("麻婆豆腐")).isEqualTo(S + "麻婆" + E + "豆腐");
    }

    @Test
    void pinyinAndNoMatchMarkNothing() {
        assertThat(Highlighter.of("mpdf").mark("Mapo Tofu")).isNull();
        assertThat(Highlighter.of("mpdf").mark("麻婆豆腐")).isNull();
        assertThat(Highlighter.of("").mark("Mapo Tofu")).isNull();
        assertThat(Highlighter.of(null).isEmpty()).isTrue();
        assertThat(Highlighter.of("tofu").mark(null)).isNull();
    }

    @Test
    void editDistanceCountsTranspositionsAsOne() {
        assertThat(Highlighter.distance("tofu", "tofu", 2)).isZero();
        assertThat(Highlighter.distance("tofu", "otfu", 2)).isEqualTo(1);
        assertThat(Highlighter.distance("noodle", "nodle", 2)).isEqualTo(1);
        assertThat(Highlighter.distance("abcdef", "uvwxyz", 2)).isEqualTo(3); // stops early past max
    }
}
