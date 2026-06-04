package ai.inquery.server.web.api.controller.dashboard.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.Dashboard;
import ai.inquery.server.domain.api.param.dashboard.DashboardCreateParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardUpdateParam;
import ai.inquery.server.web.api.controller.dashboard.request.DashboardCreateRequest;
import ai.inquery.server.web.api.controller.dashboard.request.DashboardUpdateRequest;
import ai.inquery.server.web.api.controller.dashboard.vo.DashboardVO;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.Named;

/**
 * @version DashboardWebConverter.java, v 0.1 June 9, 2023 15:45 moji Exp $
 */
@Mapper(componentModel = "spring")
public abstract class DashboardWebConverter {

    @Named("yesNoToBoolean")
    protected Boolean yesNoToBoolean(String value) {
        return "y".equalsIgnoreCase(value);
    }

    /**
     * Model conversion
     *
     * @param dashboard
     * @return
     */
    @Mapping(source = "isPublic", target = "isPublic", qualifiedByName = "yesNoToBoolean")
    public abstract DashboardVO model2vo(Dashboard dashboard);

    /**
     * Model conversion
     *
     * @param dashboards
     * @return
     */
    public abstract List<DashboardVO> model2vo(List<Dashboard> dashboards);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "userId", ignore = true),
    })
    public abstract DashboardCreateParam req2param(DashboardCreateRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "userId", ignore = true),
    })
    public abstract DashboardUpdateParam req2updateParam(DashboardUpdateRequest request);
}
