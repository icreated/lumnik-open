package io.lumnik.identity.org;

import java.math.BigDecimal;
import java.util.List;

public record OrgTreeNodeDto(
    Long id, Long parentOrgId, String code, String name,
    BigDecimal latitude, BigDecimal longitude,
    int childCount, boolean leaf,
    List<OrgTreeNodeDto> children
) {}
