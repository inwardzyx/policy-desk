package com.policydesk.api.web;

import com.policydesk.api.client.DocumentsClient;
import com.policydesk.api.client.DocumentsDto;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档接口的判据：**看任何人、写只 hr/admin**，且写操作真的带着字节转发出去。
 *
 * <p>★ 第二条最容易写反：用"能不能调内部接口"代替"这个人是不是 hr"，
 * 于是任何登录用户都能上传 —— 内部口令是服务身份，不是用户身份。
 */
@WebMvcTest(DocsController.class)
class DocsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DocumentsClient docs;

    @MockitoBean
    private JwtService jwt;

    private static JwtService.Principal p(String... roles) {
        return new JwtService.Principal(1L, "u", List.of(roles));
    }

    @Test
    @DisplayName("未登录 ⇒ 401（看也不行）")
    void list_requires_login() throws Exception {
        mvc.perform(get("/api/docs?namespace=company")).andExpect(status().isUnauthorized());
        verifyNoInteractions(docs);
    }

    @Test
    @DisplayName("employee 也能看（只读）")
    void employee_can_list() throws Exception {
        when(docs.list("company")).thenReturn(new DocumentsDto.DocList("company", List.of()));

        mvc.perform(get("/api/docs?namespace=company")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, p("employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("company"));
    }

    @Test
    @DisplayName("employee 上传 ⇒ 403，且**不转发到 Python**")
    void employee_cannot_upload() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "a.md", "text/markdown",
                "## Q\n答".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/docs").file(f)
                        .param("namespace", "company")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, p("employee")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(docs);
    }

    @Test
    @DisplayName("hr 上传 ⇒ 200，且字节原样转发（Python 吃 raw body 不是 multipart）")
    void hr_can_upload_and_bytes_are_forwarded() throws Exception {
        byte[] body = "## Q\n答".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile f = new MockMultipartFile("file", "a.md", "text/markdown", body);
        when(docs.upload(eq("company"), eq("a.md"), any(byte[].class), eq("hr")))
                .thenReturn(new DocumentsDto.UploadResult(
                        new DocumentsDto.Doc("d1", "a", "1", List.of("hr"), null, "active", false), 3));

        mvc.perform(multipart("/api/docs").file(f)
                        .param("namespace", "company").param("acl", "hr")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, p("hr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.doc_id").value("d1"))
                .andExpect(jsonPath("$.namespace_chunks").value(3));

        verify(docs).upload(eq("company"), eq("a.md"), any(byte[].class), eq("hr"));
    }

    @Test
    @DisplayName("空文件 ⇒ 400（不浪费一次解析）")
    void empty_upload_is_400() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "a.md", "text/markdown", new byte[0]);

        mvc.perform(multipart("/api/docs").file(f)
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, p("hr")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(docs);
    }

    @Test
    @DisplayName("employee 删除 ⇒ 403；hr 删除 ⇒ 200")
    void only_writers_can_delete() throws Exception {
        mvc.perform(delete("/api/docs/company/d1")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, p("employee")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(docs);

        when(docs.delete("company", "d1"))
                .thenReturn(new DocumentsDto.DeleteResult(true, "d1", "company"));
        mvc.perform(delete("/api/docs/company/d1")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, p("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }
}
