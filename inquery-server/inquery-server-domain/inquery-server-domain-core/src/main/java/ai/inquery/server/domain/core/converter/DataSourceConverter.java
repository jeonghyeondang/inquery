package ai.inquery.server.domain.core.converter;

import java.util.List;
import java.util.Map;

import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.param.ConsoleConnectParam;
import ai.inquery.server.domain.api.param.ConsoleCreateParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceCreateParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePreConnectParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceSelector;
import ai.inquery.server.domain.api.param.datasource.DataSourceTestParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceUpdateParam;
import ai.inquery.server.domain.api.service.DataSourceService;
import ai.inquery.server.domain.core.util.DesUtil;
import ai.inquery.server.domain.repository.entity.DataSourceDO;
import ai.inquery.server.tools.common.util.EasyCollectionUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;
import org.springframework.context.annotation.Lazy;

/**
 * @version DataSourceCoreConverter.java, v 0.1 September 23, 2022 15:53 moji Exp $
 */
@Slf4j
@Mapper(componentModel = "spring")
public abstract class DataSourceConverter {

    @Resource
    @Lazy
    private DataSourceService dataSourceService;

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */

    @Mapping(target = "password", expression = "java(encryptString(param))")
    @Mapping(target = "ssh",
        expression = "java(param.getSsh()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param.getSsh()))")
    @Mapping(target = "ssl",
        expression = "java(param.getSsl()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param.getSsl()))")
    @Mapping(target = "extendInfo",
        expression = "java(param.getExtendInfo()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param"
            + ".getExtendInfo()))")
    @Mapping(target = "driverConfig",
        expression = "java(param.getDriverConfig()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param"
            + ".getDriverConfig()))")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "gmtCreate", ignore = true)
    @Mapping(target = "gmtModified", ignore = true)
    @Mapping(target = "userId", ignore = true)
    public abstract DataSourceDO param2do(DataSourceCreateParam param);

    /**
     * encrypt
     *
     * @param param
     * @return
     */
    protected String encryptString(DataSourceCreateParam param) {
        String encryptStr = param.getPassword();
        if (StringUtils.isNotBlank(encryptStr)) {
            try {
                DesUtil desUtil = new DesUtil(DesUtil.DES_KEY);
                encryptStr = desUtil.encrypt(param.getPassword(), "CBC");
            } catch (Exception exception) {
                // do nothing
                log.error("encrypt error", exception);
            }
        }
        return encryptStr;
    }

    /**
     * encrypt
     *
     * @param param
     * @return
     */
    protected String encryptString(DataSourceUpdateParam param) {
        String encryptStr = param.getPassword();
        if (StringUtils.isNotBlank(encryptStr)) {
            try {
                DesUtil desUtil = new DesUtil(DesUtil.DES_KEY);
                encryptStr = desUtil.encrypt(param.getPassword(), "CBC");
            } catch (Exception exception) {
                log.error("encrypt error", exception);
            }
        }
        return encryptStr;
    }

    /**
     * decrypt
     *
     * @param param
     * @return
     */
    protected String decryptString(DataSourceDO param) {
        String decryptStr = param.getPassword();
        if (StringUtils.isNotBlank(decryptStr)) {
            try {
                DesUtil desUtil = new DesUtil(DesUtil.DES_KEY);
                decryptStr = desUtil.decrypt(param.getPassword(), "CBC");
            } catch (Exception exception) {
                log.error("decrypt error", exception);
            }
        }
        return decryptStr;
    }

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(target = "password", expression = "java(encryptString(param))"),
        @Mapping(target = "ssh",
            expression = "java(param.getSsh()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param.getSsh()))"),
        @Mapping(target = "ssl",
            expression = "java(param.getSsl()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param.getSsl()))"),
        @Mapping(target = "extendInfo",
            expression = "java(param.getExtendInfo()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param"
                + ".getExtendInfo()))"),
        @Mapping(target = "driverConfig",
            expression = "java(param.getDriverConfig()==null?null: com.alibaba.fastjson2.JSON.toJSONString(param"
                + ".getDriverConfig()))"),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "userId", ignore = true),
        @Mapping(target = "kind", ignore = true),
    })
    public abstract DataSourceDO param2do(DataSourceUpdateParam param);

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    public abstract ConsoleCreateParam param2consoleParam(ConsoleConnectParam param);

    /**
     * Parameter conversion
     *
     * @param dataSourcePreConnectParam
     * @return
     */
    @Mappings({
        @Mapping(source = "type", target = "dbType"),
        @Mapping(source = "user", target = "username")
    })
    public abstract DataSourceTestParam param2param(
        DataSourcePreConnectParam dataSourcePreConnectParam);

    /**
     * Model conversion
     *
     * @param dataSourceDO
     * @return
     */

    @Mapping(target = "password", expression = "java(decryptString(dataSourceDO))")
    @Mapping(target = "ssh",
        expression = "java(com.alibaba.fastjson2.JSON.parseObject(dataSourceDO.getSsh(),ai.inquery.spi"
            + ".model.SSHInfo.class))")
    @Mapping(target = "ssl",
        expression =
            "java(com.alibaba.fastjson2.JSON.parseObject(dataSourceDO.getSsl(),ai.inquery.spi"
                + ".model.SSLInfo"
                + ".class))")
    @Mapping(target = "driverConfig",
        expression =
            "java(com.alibaba.fastjson2.JSON.parseObject(dataSourceDO.getDriverConfig(),ai.inquery.spi.config"
                + ".DriverConfig"
                + ".class))")
    @Mapping(target = "extendInfo",
        expression = "java(com.alibaba.fastjson2.JSON.parseArray(dataSourceDO.getExtendInfo(),ai.inquery.spi.model"
            + ".KeyValue.class))")
    @Mapping(target = "environment.id", source = "environmentId")
    @Mapping(target = "supportDatabase", ignore = true)
    @Mapping(target = "supportSchema", ignore = true)
    @Mapping(target = "extendMap", ignore = true)
    public abstract DataSource do2dto(DataSourceDO dataSourceDO);

    /**
     * Model conversion
     *
     * @param dataSourceDOList
     * @return
     */
    public abstract List<DataSource> do2dto(List<DataSourceDO> dataSourceDOList);

    /**
     * Fill in detailed information
     *
     * @param list
     */
    public void fillDetail(List<DataSource> list) {
        fillDetail(list, null);
    }

    /**
     * Fill in detailed information
     *
     * @param list
     */
    public void fillDetail(List<DataSource> list, DataSourceSelector selector) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        List<Long> idList = EasyCollectionUtils.toList(list, DataSource::getId);
        List<DataSource> queryList = dataSourceService.listQuery(idList, selector).getData();
        Map<Long, DataSource> queryMap = EasyCollectionUtils.toIdentityMap(queryList, DataSource::getId);
        for (DataSource data : list) {
            if (data == null || data.getId() == null) {
                continue;
            }
            DataSource query = queryMap.get(data.getId());
            add(data, query);
        }
    }

    @Mappings({
        @Mapping(target = "id", ignore = true),
    })
    public abstract void add(@MappingTarget DataSource target, DataSource source);
}
