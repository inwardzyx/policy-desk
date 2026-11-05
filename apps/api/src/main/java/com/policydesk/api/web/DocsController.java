package com.policydesk.api.web;

import com.policydesk.api.client.DocumentsClient;
import com.policydesk.api.client.DocumentsDto;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 文档管理：列表 / 上传 / 删除。
 *
 * <p>★ 权限分两档（这是 M6b 唯一新增的授权判断）：
 * <ul>
 *   <li><b>看</b>：任何登录用户；</li>
 *   <li><b>写</b>（上传/删除）：只有 hr / admin。</li>
 * </ul>
 * ★ 不要用"能不能调 Python 的写接口"来代替这条判断：内部口令是**服务身份**，
 * 它证明"请求来自 Java"，不证明"这个人是 hr"。两者混用 = 任何登录用户都能写库。
 */
@RestController
@RequestMapping("/api/docs")
public class DocsController {

    /** 能写文档的角色。与 {@code AclProjector} 的角色表保持一致。 */
    private static final Set<String> WRITERS = Set.of("hr", "admin");

    private final DocumentsClient docs;

    public DocsController(DocumentsClient docs) {
        this.docs = docs;
    }

    public record UploadResponse(DocumentsDto.Doc document, Integer namespace_chunks) {
    }

    @GetMapping
    public DocumentsDto.DocList list(HttpServletRequest request,
                                     @RequestParam(defaultValue = "company") String namespace) {
        requireUser(request);       // 只要求登录，不要求角色
        return docs.list(namespace);
    }

    @PostMapping(consumes = "multipart/form-data")
    public UploadResponse upload(HttpServletRequest request,
                                @RequestPart("file") MultipartFile file,
                                @RequestParam(defaultValue = "company") String namespace,
                                @RequestParam(defaultValue = "public") String acl) {
        requireWriter(request);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        try {
            DocumentsDto.UploadResult r = docs.upload(namespace, file.getOriginalFilename(),
                    file.getBytes(), acl);
            return new UploadResponse(r.document(), r.namespace_chunks());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
    }

    @DeleteMapping("/{namespace}/{docId}")
    public DocumentsDto.DeleteResult delete(HttpServletRequest request,
                                            @PathVariable String namespace,
                                            @PathVariable String docId) {
        requireWriter(request);
        return docs.delete(namespace, docId);
    }

    private JwtService.Principal requireUser(HttpServletRequest request) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }
        return p;
    }

    private void requireWriter(HttpServletRequest request) {
        JwtService.Principal p = requireUser(request);
        List<String> roles = p.roles();
        boolean ok = roles != null && roles.stream().anyMatch(WRITERS::contains);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有 hr / admin 能上传或删除文档");
        }
    }
}
