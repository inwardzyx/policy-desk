package com.policydesk.api.client;

import com.policydesk.api.config.PythonAiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 文档相关的内部调用（列表 / 上传 / 删除）。
 *
 * <p>★ 为什么单独一个类：{@link PythonAiClient} 负责问答这条主干，
 * 文档是另一条支路（multipart、原始字节、查询参数）。拆开后问答的测试不跟着文档字段变。
 *
 * <p>★ 三个方法都只带 {@code X-Internal-Token}。**"是 Java 在调" 与 "这个用户能不能上传"
 * 是两件事**：后者在 {@code DocsController} 里按角色判 —— 混在一起就会出现
 * "持有内部口令的人等于有写权限"。
 */
@Component
public class DocumentsClient {

    private final RestClient http;
    private final String internalToken;

    public DocumentsClient(RestClient pythonAiRestClient, PythonAiProperties props) {
        this.http = pythonAiRestClient;
        this.internalToken = props.internalToken();
    }

    public DocumentsDto.DocList list(String namespace) {
        try {
            return http.get()
                    .uri("/internal/kb/{ns}/documents", namespace)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(DocumentsDto.DocList.class);
        } catch (RestClientResponseException e) {
            throw new PythonAiClient.PythonAiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), e);
        }
    }

    /**
     * 上传一篇文档。
     *
     * <p>★ Python 那条接口吃的是 **raw body + query 参数**（不是 multipart），
     * 所以这里把前端传来的字节原样转发，文件名与 acl 走 query。
     * 前端用 multipart 是浏览器 `<input type=file>` 的自然形式，转换在这一层做。
     */
    public DocumentsDto.UploadResult upload(String namespace, String filename, byte[] bytes, String acl) {
        URI uri = UriComponentsBuilder.fromPath("/internal/kb/{ns}/documents")
                .queryParam("filename", filename)
                .queryParam("acl", acl == null || acl.isBlank() ? "public" : acl)
                .buildAndExpand(namespace)
                .encode()
                .toUri();
        try {
            return http.post()
                    .uri(uri)
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes)
                    .retrieve()
                    .body(DocumentsDto.UploadResult.class);
        } catch (RestClientResponseException e) {
            throw new PythonAiClient.PythonAiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), e);
        }
    }

    public DocumentsDto.DeleteResult delete(String namespace, String docId) {
        try {
            return http.delete()
                    .uri("/internal/kb/{ns}/documents/{id}", namespace, docId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(DocumentsDto.DeleteResult.class);
        } catch (RestClientResponseException e) {
            throw new PythonAiClient.PythonAiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), e);
        }
    }
}
