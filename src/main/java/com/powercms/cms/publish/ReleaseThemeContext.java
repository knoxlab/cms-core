package com.powercms.cms.publish;

import com.powercms.cms.publish.entity.ReleaseThemeVersionEntity;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.util.*;

/** Immutable theme revision closure used while rendering a frozen release. */
public final class ReleaseThemeContext {
    public record Revision(UUID revisionId, UUID themeId, int themeVersion, String referenceRole) {}
    private final Map<UUID, Revision> byThemeId;
    private ReleaseThemeContext(Map<UUID, Revision> byThemeId) { this.byThemeId = Map.copyOf(byThemeId); }
    public static ReleaseThemeContext from(Collection<ReleaseThemeVersionEntity> pins) {
        Map<UUID, Revision> revisions = new LinkedHashMap<>();
        for (ReleaseThemeVersionEntity pin : pins) {
            Revision previous = revisions.putIfAbsent(pin.getThemeId(), new Revision(pin.getThemeRevisionId(), pin.getThemeId(), pin.getThemeVersion(), pin.getReferenceRole()));
            if (previous != null && !previous.revisionId().equals(pin.getThemeRevisionId()))
                throw new BusinessException(ErrorCodes.CONFLICT, "\u540c\u4e00\u4e3b\u9898\u5728 Release \u7ee7\u627f\u95ed\u5305\u4e2d\u51fa\u73b0\u591a\u4e2a revision");
        }
        return new ReleaseThemeContext(revisions);
    }
    public Revision require(UUID themeId) {
        Revision revision = byThemeId.get(themeId);
        if (revision == null) throw new BusinessException(ErrorCodes.CONFLICT, "Release \u4e3b\u9898\u95ed\u5305\u7f3a\u5c11 revision: " + themeId);
        return revision;
    }
    public Collection<Revision> revisions() { return byThemeId.values(); }
}
