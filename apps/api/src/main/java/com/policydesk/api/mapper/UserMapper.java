package com.policydesk.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.policydesk.api.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * users 表的读写。
 *
 * <p>★ 用 MyBatis-Plus 的 {@link BaseMapper} 而不是手写 XML：
 * 这一个接口就覆盖了增删改查，SQL 由框架生成（可预期、可分页、可加条件构造器）。
 * 国内 JD 点名 MyBatis/MyBatis-Plus 的居多，而这套 CRUD 也正是实习期最常见的活。
 *
 * <p>★ 只保留一个自定义方法：按用户名查。别的方法一律用 MP 的 lambda 查询 ——
 * 手写 SQL 越少，"改了表忘了改 SQL"的机会越少。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
