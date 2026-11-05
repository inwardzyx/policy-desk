package com.policydesk.api.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → ACL 的**唯一映射点**。
 *
 * <p>为什么这里值得单独一个类：这是"身份"和"数据"之间的转换层，
 * 也是最容易出错的一层 —— 写反了就是越权读，写宽了就是数据泄漏。
 * 把它做成一个纯函数（无 IO、无状态），才能被穷举测试。
 *
 * <p>★ 三条规定（都配了用例）：
 * <ol>
 *   <li><b>每个用户都自带 public</b>：不写死在各处，只在这里加一次；</li>
 *   <li><b>多角色取并集</b>：hr + employee 应该同时看得见两类条款；</li>
 *   <li><b>未知角色 ⇒ 抛错（403），不是悄悄降级成 public</b>：
 *       拼错一个角色名（{@code HRR}）时，静默降级会让"本该看见的人看不见"，
 *       而且很难查；显式失败则一眼看到"这个角色没登记"。</li>
 * </ol>
 *
 * <p>★ 顺序是固定的（{@code ACL_ORDER}），不依赖入参顺序 —— 否则同一个用户
 * 两次请求发出的头字符串可能不同，而头里是签名/日志要对比的东西。
 */
@Component
public class AclProjector {

    /** 已知 ACL 的**规范顺序**：发出去的头字符串必须稳定。 */
    private static final List<String> ACL_ORDER = List.of("hr", "finance", "public");

    /**
     * 角色 → 该角色拥有的 ACL。
     *
     * <p>★ 目前是硬编码的静态策略：角色只有三个、ACL 只有三个。
     * 一旦要做"每租户自定义角色"，这里换成从库里读 —— 但**接口不变**，
     * 调用方（{@link com.policydesk.api.web.ChatController}）不用改。
     */
    private static final Map<String, List<String>> ROLE_TO_ACL = Map.of(
            "employee", List.of("public"),
            "hr", List.of("hr", "public"),
            "admin", List.of("hr", "finance", "public")
    );

    /** 按规范顺序返回该用户的 ACL。 */
    public List<String> project(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new UnknownRole("令牌里没有任何角色");
        }
        Set<String> granted = new LinkedHashSet<>();
        for (String role : roles) {
            List<String> acls = ROLE_TO_ACL.get(role);
            if (acls == null) {
                throw new UnknownRole("角色 " + role + " 没有登记 ACL 映射（拼错了？）");
            }
            granted.addAll(acls);
        }
        List<String> out = new ArrayList<>(granted.size());
        for (String acl : ACL_ORDER) {          // 按规范顺序输出，保证头字符串稳定
            if (granted.contains(acl)) {
                out.add(acl);
            }
        }
        return out;
    }

    /** 角色没登记。对外是 403（不是 401：身份是真的，只是权限没定义）。 */
    public static class UnknownRole extends RuntimeException {
        public UnknownRole(String message) {
            super(message);
        }
    }
}
