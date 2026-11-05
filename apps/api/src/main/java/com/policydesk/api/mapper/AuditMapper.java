package com.policydesk.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.policydesk.api.entity.AuditRecord;
import org.apache.ibatis.annotations.Mapper;

/** audit 表的读写。 */
@Mapper
public interface AuditMapper extends BaseMapper<AuditRecord> {
}
