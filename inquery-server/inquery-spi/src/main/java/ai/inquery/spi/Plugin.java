
package ai.inquery.spi;

import ai.inquery.spi.config.DBConfig;

/**
 */
public interface Plugin {

    /**
     * Get DB configuration information.
     *
     * @return
     */
    DBConfig getDBConfig();

    /**
     * Query db metadata information.
     *
     * @return
     */
    MetaData getMetaData();

    /**
     *
     * @return
     */
    DBManage getDBManage();

}