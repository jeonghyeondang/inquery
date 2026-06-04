package ai.inquery.server.common.api.controller.vo;

import java.io.Serial;
import java.io.Serializable;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * user
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class
SimpleUserVO implements Serializable {
    @Serial
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;

    /**
     * primary key
     */
    private Long id;

    /**
     * username
     */
    private String userName;

    /**
     * Nick name
     */
    private String nickName;
}