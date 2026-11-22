package com.we.sharesystem.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 敏感词过滤（DFA / Trie 字典树）。
 *
 * 为什么不用 String.contains 逐个词扫：
 * 词库上百条时，每发一条内容要做 N 次全文扫描，复杂度 O(词数 × 文本长度)。
 * DFA 把词库建成树，一次遍历文本即可，复杂度只与文本长度有关（O(n × 词长)），
 * 词库扩到几千条也不影响响应时间。
 *
 * 词库在 resources/sensitive-words.txt，改完重启生效。
 */
@Component
@Slf4j
public class SensitiveWordFilter {

    /** 单词最长长度，限制单次匹配的回溯深度 */
    private static final int MAX_WORD_LEN = 32;
    private static final String DICT_FILE = "sensitive-words.txt";

    private final TrieNode root = new TrieNode();

    @PostConstruct
    public void init() {
        int count = 0;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DICT_FILE)) {
            if (in == null) {
                log.warn("未找到敏感词库 {}，过滤功能将不生效", DICT_FILE);
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (word.isEmpty() || word.startsWith("#")) {
                    continue;
                }
                insert(normalize(word));
                count++;
            }
        } catch (Exception e) {
            log.error("加载敏感词库失败", e);
        }
        log.info("敏感词库加载完成，共 {} 条", count);
    }

    /** 文本是否命中敏感词 */
    public boolean hit(String text) {
        return firstHit(text) != null;
    }

    /** 返回命中的第一个词（只用于日志排查，不要回显给用户，避免被针对性规避） */
    public String firstHit(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String normalized = normalize(text);
        int n = normalized.length();
        for (int i = 0; i < n; i++) {
            TrieNode node = root;
            int limit = Math.min(n, i + MAX_WORD_LEN);
            for (int j = i; j < limit; j++) {
                node = node.next.get(normalized.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.end) {
                    return normalized.substring(i, j + 1);
                }
            }
        }
        return null;
    }

    /**
     * 归一化：去空白、去常见分隔符、转小写。
     * 「刷 单」「刷-单」「刷.单」都能还原成「刷单」，堵住最省事的绕过手法。
     */
    private String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            switch (c) {
                case '-', '_', '.', '*', '·', '/', '\\', '|', '~', '"', '\'', '(', ')', '[', ']' -> {
                    continue;
                }
                default -> {
                }
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private void insert(String word) {
        if (word.isEmpty()) {
            return;
        }
        TrieNode node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.next.computeIfAbsent(word.charAt(i), k -> new TrieNode());
        }
        node.end = true;
    }

    private static class TrieNode {
        /** 子节点；词库是中文为主，初始容量给 2 够用 */
        private final Map<Character, TrieNode> next = new HashMap<>(4);
        /** 是否有词在此结束 */
        private boolean end;
    }
}
