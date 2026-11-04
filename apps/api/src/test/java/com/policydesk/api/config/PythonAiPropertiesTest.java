package com.policydesk.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置的 fail-closed 判据。
 *
 * <p>★ 这三条如果只写在文档里，等于没有：真正的风险是"某次部署忘了设 INTERNAL_TOKEN"，
 * 而那时的现象是**用户提问才 500**。这里让它在构造对象时就炸。
 */
class PythonAiPropertiesTest {

    @Test
    @DisplayName("缺内部口令 ⇒ 构造即失败（不是等到第一次请求）")
    void blank_token_is_rejected() {
        assertThatThrownBy(() -> new PythonAiProperties("http://ai", "  ", 2000, 60000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_TOKEN");
    }

    @Test
    @DisplayName("缺 base-url ⇒ 构造即失败")
    void blank_base_url_is_rejected() {
        assertThatThrownBy(() -> new PythonAiProperties("", "t0ken", 2000, 60000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    @DisplayName("超时给 0/负数时退回默认值，而不是变成一直等")
    void non_positive_timeouts_fall_back() {
        PythonAiProperties p = new PythonAiProperties("http://ai", "t0ken", 0, -1);
        assertThat(p.connectTimeoutMs()).isPositive();
        assertThat(p.readTimeoutMs()).isPositive();
    }
}
