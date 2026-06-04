package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * User entity
 */
@Getter
@Setter
@TableName("inquery_user")
public class InqueryUserDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Date gmtCreate;

    private Date gmtModified;

    private String userName;

    private String password;

    private String nickName;

    private String email;

    private String roleCode;

    private String status;

    private Long createUserId;

    private Long modifiedUserId;
}
