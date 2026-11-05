package com.policydesk.api.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 文档接口的响应形状（与 Python 侧 manifest 对齐）。
 *
 * <p>★ 字段名用 snake_case：Python 那边就是这么返回的。
 * 不要为了"Java 风格"改成 camelCase 再手动映射 —— 那只是多一处会写错的地方。
 *
 * <p>★ 一律 {@code ignoreUnknown}：Python 在 manifest 里加字段（已经加过
 * {@code effective_date}、{@code status}）时，这里不该炸。
 */
public final class DocumentsDto {

    private DocumentsDto() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Doc(
            String doc_id,
            String title,
            String version,
            List<String> acl,
            String updated_at,
            String status,
            Boolean tombstone
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocList(String namespace, List<Doc> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UploadResult(Doc document, Integer namespace_chunks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteResult(Boolean deleted, String doc_id, String namespace) {
    }
}
