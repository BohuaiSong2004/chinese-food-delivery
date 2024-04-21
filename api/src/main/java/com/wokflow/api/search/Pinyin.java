package com.wokflow.api.search;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * Toneless pinyin for Chinese dish names so that "mapo doufu", "mapodoufu" and
 * the initials "mpdf" all find 麻婆豆腐. Heteronyms use pinyin4j's first reading,
 * which is the common one for food words.
 */
public final class Pinyin {
    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private Pinyin() {}

    public record Reading(String syllables, String joined, String initials) {}

    public static Reading of(String chinese) {
        StringBuilder syllables = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (char c : chinese.toCharArray()) {
            String py = syllable(c);
            if (py == null) {
                if (Character.isLetterOrDigit(c)) {
                    append(syllables, String.valueOf(Character.toLowerCase(c)));
                }
                continue;
            }
            append(syllables, py);
            initials.append(py.charAt(0));
        }
        String s = syllables.toString();
        return new Reading(s, s.replace(" ", ""), initials.toString());
    }

    private static void append(StringBuilder sb, String part) {
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(part);
    }

    private static String syllable(char c) {
        try {
            String[] all = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
            return all == null || all.length == 0 ? null : all[0];
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            throw new IllegalStateException(e);
        }
    }
}
