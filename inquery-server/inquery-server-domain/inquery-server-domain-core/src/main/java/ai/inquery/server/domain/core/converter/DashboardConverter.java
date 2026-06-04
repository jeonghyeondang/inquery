package ai.inquery.server.domain.core.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.Dashboard;
import ai.inquery.server.domain.api.param.dashboard.DashboardCreateParam;
import ai.inquery.server.domain.api.param.dashboard.DashboardUpdateParam;
import ai.inquery.server.domain.repository.entity.DashboardDO;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * @version ChartConverter.java, v 0.1 June 9, 2023 17:13 moji Exp $
 */
@Mapper(componentModel = "spring")
public abstract class DashboardConverter {

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    @Mapping(target = "id", ignore = true)
    public abstract DashboardDO param2do(DashboardCreateParam param);

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    public abstract DashboardDO updateParam2do(DashboardUpdateParam param);

    /**
     * Model conversion
     *
     * @param chartDO
     * @return
     */
    @Mapping(target = "chartIds", ignore = true)
    public abstract Dashboard do2model(DashboardDO chartDO);

    /**
     * Model conversion
     *
     * @param chartDOS
     * @return
     */
    public abstract List<Dashboard> do2model(List<DashboardDO> chartDOS);
}
