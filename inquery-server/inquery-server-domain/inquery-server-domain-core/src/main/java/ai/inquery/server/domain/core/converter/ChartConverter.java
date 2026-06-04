package ai.inquery.server.domain.core.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.Chart;
import ai.inquery.server.domain.api.chart.ChartCreateParam;
import ai.inquery.server.domain.api.chart.ChartUpdateParam;
import ai.inquery.server.domain.repository.entity.ChartDO;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @version ChartConverter.java, v 0.1 June 9, 2023 17:13 moji Exp $
 */
@Mapper(componentModel = "spring")
public abstract class ChartConverter {

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "description", ignore = true),
    })
    public abstract ChartDO param2do(ChartCreateParam param);

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(target = "description", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "sourceType", ignore = true),
    })
    public abstract ChartDO updateParam2do(ChartUpdateParam param);

    /**
     * Model conversion
     *
     * @param chartDO
     * @return
     */
    @Mapping(target = "dataSourceName", ignore = true)
    public abstract Chart do2model(ChartDO chartDO);

    /**
     * Model conversion
     *
     * @param chartDOS
     * @return
     */
    public abstract List<Chart> do2model(List<ChartDO> chartDOS);
}
