package com.wokflow.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SynonymsTest {
    @Test
    void groupsWorkBothWaysAndArrowsOneWay() {
        assertThat(Synonyms.variants("boba")).contains("bubble milk tea", "pearl milk tea");
        assertThat(Synonyms.variants("bubble milk tea")).contains("boba");
        assertThat(Synonyms.variants("ha gow")).contains("har gow", "shrimp dumplings");
        assertThat(Synonyms.variants("shrimp dumplings")).doesNotContain("har gow");
        assertThat(Synonyms.variants("面条")).containsExactly("面");
        assertThat(Synonyms.variants("面")).isEmpty();
    }

    @Test
    void replacesOnlyThePhraseAndKeepsTheRestOfTheQuery() {
        assertThat(Synonyms.variants("Kung Pow  Chicken")).contains("kung pao chicken", "gong bao chicken");
        assertThat(Synonyms.variants("lamb kebab")).contains("lamb skewers");
        assertThat(Synonyms.variants("馄饨汤")).contains("云吞汤", "抄手汤");
    }

    @Test
    void latinPhrasesMatchWholeWordsOnly() {
        assertThat(Synonyms.variants("tofurky")).isEmpty();      // "tofu" inside a word
        assertThat(Synonyms.variants("chilean")).isEmpty();      // "chile"
        assertThat(Synonyms.variants("mapo tofu")).contains("mapo bean curd");
    }

    @Test
    void longestPhraseWinsAndVariantsAreCapped() {
        // "pearl milk tea" is one phrase, not "milk tea" plus leftovers
        assertThat(Synonyms.variants("pearl milk tea")).contains("boba").doesNotContain("pearl boba");
        assertThat(Synonyms.variants("kebab skewer chilli tofu wonton")).hasSizeLessThanOrEqualTo(Synonyms.MAX_VARIANTS);
        assertThat(Synonyms.variants("  ")).isEmpty();
        assertThat(Synonyms.variants(null)).isEmpty();
    }

    @Test
    void parsesCommentsBlankLinesAndArrows() {
        var rules = Synonyms.parse(List.of("# comment", "", "a, b => c", "x, y"));
        assertThat(rules).extracting(Synonyms.Rule::from).containsExactlyInAnyOrder("a", "b", "x", "y");
        assertThat(rules).filteredOn(r -> r.from().equals("a")).singleElement()
                .extracting(Synonyms.Rule::to).isEqualTo(List.of("b", "c"));
        assertThat(Synonyms.ruleCount()).isGreaterThan(50);
    }
}
