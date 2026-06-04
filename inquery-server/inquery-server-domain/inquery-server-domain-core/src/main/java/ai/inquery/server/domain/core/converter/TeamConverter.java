package ai.inquery.server.domain.core.converter;

import java.util.List;
import java.util.Map;

import ai.inquery.server.domain.api.model.Team;
import ai.inquery.server.domain.api.param.team.TeamCreateParam;
import ai.inquery.server.domain.api.param.team.TeamUpdateParam;
import ai.inquery.server.domain.api.service.TeamService;
import ai.inquery.server.domain.repository.entity.TeamDO;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;
import org.springframework.context.annotation.Lazy;

/**
 * converter
 *
 */
@Slf4j
@Mapper(componentModel = "spring")
public abstract class TeamConverter {

    @Resource
    @Lazy
    private TeamService teamService;

    /**
     * convert
     *
     * @param list
     * @return
     */
    public abstract List<Team> do2dto(List<TeamDO> list);

    /**
     * convert
     *
     * @param data
     * @return
     */
    @Mappings({
        @Mapping(target = "modifiedUser.id", source = "modifiedUserId"),
    })
    public abstract Team do2dto(TeamDO data);

    /**
     * convert
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(target = "createUserId", source = "userId"),
        @Mapping(target = "modifiedUserId", source = "userId"),
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
    })
    public abstract TeamDO param2do(TeamCreateParam param, Long userId);

    /**
     * convert
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(target = "modifiedUserId", source = "userId"),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "createUserId", ignore = true),
        @Mapping(target = "code", ignore = true),
    })
    public abstract TeamDO param2do(TeamUpdateParam param, Long userId);

    /**
     * Fill in detailed information
     *
     * @param list
     */
    public void fillDetail(List<Team> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        List<Long> idList = EasyCollectionUtils.toList(list, Team::getId);
        List<Team> queryList = teamService.listQuery(idList).getData();
        Map<Long, Team> queryMap = EasyCollectionUtils.toIdentityMap(queryList, Team::getId);
        for (Team data : list) {
            if (data == null || data.getId() == null) {
                continue;
            }
            Team query = queryMap.get(data.getId());
            add(data, query);
        }
    }

    @Mappings({
        @Mapping(target = "id", ignore = true),
    })
    public abstract void add(@MappingTarget Team target, Team source);
}
