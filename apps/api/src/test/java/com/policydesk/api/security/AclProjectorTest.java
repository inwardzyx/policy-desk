package com.policydesk.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投影判据：三个角色、并集、顺序稳定、未知角色必须炸。
 *
 * <p>★ 这一层出错不会立刻报错 —— 它会安静地多给或少给一条 ACL。
 * 所以宁可把"不该发生"的输入也测一遍（空角色、拼错角色）。
 */
class AclProjectorTest {

    private final AclProjector projector = new AclProjector();

    @Test
    @DisplayName("employee ⇒ 只有 public")
    void employee_gets_public_only() {
        assertThat(projector.project(List.of("employee"))).containsExactly("public");
    }

    @Test
    @DisplayName("hr ⇒ hr + public")
    void hr_gets_hr_and_public() {
        assertThat(projector.project(List.of("hr"))).containsExactly("hr", "public");
    }

    @Test
    @DisplayName("admin ⇒ 全部（且按规范顺序，不随入参顺序变）")
    void admin_gets_everything_in_canonical_order() {
        assertThat(projector.project(List.of("admin")))
                .containsExactly("hr", "finance", "public");
    }

    @Test
    @DisplayName("多角色取并集，不重复")
    void multiple_roles_are_unioned() {
        assertThat(projector.project(List.of("employee", "hr"))).containsExactly("hr", "public");
        assertThat(projector.project(List.of("hr", "employee"))).containsExactly("hr", "public");
    }

    @Test
    @DisplayName("未知角色 ⇒ 抛错（403），**不许悄悄降级成 public**")
    void unknown_role_throws() {
        assertThatThrownBy(() -> projector.project(List.of("HRR")))
                .isInstanceOf(AclProjector.UnknownRole.class)
                .hasMessageContaining("HRR");
    }

    @Test
    @DisplayName("空角色 ⇒ 抛错（令牌里没角色本身就是异常的）")
    void empty_roles_throw() {
        assertThatThrownBy(() -> projector.project(List.of()))
                .isInstanceOf(AclProjector.UnknownRole.class);
    }
}
