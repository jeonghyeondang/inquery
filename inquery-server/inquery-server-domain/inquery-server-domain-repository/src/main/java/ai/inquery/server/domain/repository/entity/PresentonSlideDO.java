package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Presenton Slide Entity
 */
@Data
@TableName("presenton_slide")
public class PresentonSlideDO {
    
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    
    @TableField("gmt_create")
    private Date gmtCreate;
    
    @TableField("gmt_modified")
    private Date gmtModified;
    
    @TableField("presentation_id")
    private String presentationId;
    
    @TableField("slide_index")
    private Integer slideIndex;
    
    @TableField("layout_group")
    private String layoutGroup;
    
    @TableField("layout")
    private String layout;
    
    @TableField("content_json")
    private String contentJson;
    
    @TableField("speaker_note")
    private String speakerNote;
}
