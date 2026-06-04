
package ai.inquery.server.web.api.controller.user.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class UserUpdateRequest {

    /**
     * primary key
     */
    private Long id;

    /**
     * userName
     */
    private String userName;

    /**
     * password
     */
    private String password;

    /**
     * nickName
     */
    private String nickName;


    /**
     * email
     */
    private String email;
}